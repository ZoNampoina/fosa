"""FOSA Audio Bridge, experimental native ASIO capture and local WebRTC server."""
import os
os.environ.setdefault("SD_ENABLE_ASIO", "1")  # Must precede sounddevice import.
import argparse
import asyncio
import contextlib
from collections import deque
from fractions import Fraction
from concurrent.futures import ThreadPoolExecutor
import hmac
import io
import ipaddress
import json
from pathlib import Path
import secrets
import socket
import ssl
import time
import uuid
import webbrowser
from urllib.parse import urlencode, urlsplit

import numpy as np
import psutil
try:
    import sounddevice as sd
    CAPTURE_IMPORT_ERROR = ''
except (ImportError, OSError) as e:
    sd = None
    CAPTURE_IMPORT_ERROR = str(e)
from aiohttp import web, ClientSession
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, MediaStreamTrack, RTCRtpSender
from av import AudioFrame, AudioResampler
import qrcode
from qrcode.image.svg import SvgPathImage
from mixer import CHANNELS, RATE, FRAME, clean_mix, default_mix, number
from monitor_engine import MonitorEngine
from audio_devices import describe
from native_audio import NativeProtocol, NativeSender
from discovery import StageDiscovery
from lan import discover, configure_firewall
from secure_relay import RelayIdentity, SecureRelay
from low_latency import PcmSender, SAMPLES as PCM_FRAME
CAPTURE_ERRORS = (ValueError, KeyError, TypeError, OSError) + ((sd.PortAudioError,) if sd else ())

ROOT = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"
VERSION = "0.10.0-bodypack"

# aiohttp uses the standard json module, which does not serialize NumPy scalar types.
# Normalize them centrally so device/status endpoints cannot fail on np.bool_, np.int*, etc.
def _json_default(value):
    if isinstance(value, np.generic):
        return value.item()
    if isinstance(value, np.ndarray):
        return value.tolist()
    raise TypeError(f"Object of type {type(value).__name__} is not JSON serializable")

def _json_dumps(data):
    return json.dumps(data, default=_json_default)

def json_response(data, **kwargs):
    return web.json_response(data, dumps=_json_dumps, **kwargs)

class MusicianRequest:
    """Internal RPC adapter; never constructed from a public HTTP header."""
    remote = None
    def __init__(self, token, body):
        self.headers = {"Authorization": "Bearer " + token}
        self.body = body
    async def json(self):
        return self.body

class StereoTrack(MediaStreamTrack):
    kind = "audio"
    def __init__(self):
        super().__init__()
        self.queue = asyncio.Queue(maxsize=2)
        self.pts = 0
        self.dropped = 0
    def feed(self, data):
        if self.readyState != "live":
            return
        if self.queue.full():
            self.queue.get_nowait()
            self.pts += FRAME
            self.dropped += 1
        self.queue.put_nowait(data)
    async def recv(self):
        data = await self.queue.get()
        pcm = np.ascontiguousarray((np.clip(data, -1, 1) * 32767).astype(np.int16).reshape(1, -1))
        frame = AudioFrame.from_ndarray(pcm, format="s16", layout="stereo")
        frame.sample_rate = RATE
        frame.pts = self.pts
        frame.time_base = Fraction(1, RATE)
        self.pts += FRAME
        return frame

class Bridge:
    def __init__(self, args):
        self.args = args
        self.data = Path(args.data_dir)
        self.data.mkdir(parents=True, exist_ok=True)
        self.file = self.data / "state.json"
        try:
            self.saved = json.loads(self.file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.saved = {}
        self.saved.setdefault("admin", secrets.token_urlsafe(32))
        self.saved.setdefault("joinCode", secrets.token_hex(4).upper())
        self.saved.setdefault("profiles", {})
        self.saved.setdefault("session", str(uuid.uuid4()))
        self.saved.setdefault("channels", [{"number": i + 1, "name": f"CH {i+1:02}", "role": "", "icon": ""} for i in range(CHANNELS)])
        self.channel_count = len(self.saved['channels'])
        for profile in self.saved['profiles'].values():
            profile['mix'] = clean_mix(profile['mix'], self.channel_count)
        self.alternative = False
        self.panic = self.saved.get("panic") is True
        self.events = deque(maxlen=1000)
        self.dsp_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix='FOSA-DSP')
        self.save_lock = asyncio.Lock()
        self.native = NativeProtocol()
        self.discovery = StageDiscovery()
        self.native_port = None
        self.audio_cpu = None
        self.callback_ms = 0.
        self.processing_ms = 0.
        self.startup = 'Detecting MR18'
        self.stream = None
        self.device = None
        self.wanted = None
        self.buffer = 128 if getattr(args, 'low_latency', False) else 256
        self.raw = deque(maxlen=32)
        self.capture_loop = None
        self.capture_event = None
        self.capture_wake_pending = False
        self.last_capture = 0
        self.last_retry = 0
        self.peaks = np.zeros(self.channel_count)
        self.rms = np.zeros(self.channel_count)
        self.clip_at = np.zeros(self.channel_count)
        self.error = "Capture indisponible : " + CAPTURE_IMPORT_ERROR if CAPTURE_IMPORT_ERROR else "MR18 non connectée. Sélectionnez une interface."
        self.xruns = 0
        self.capture_drops = 0
        self.run_id = secrets.token_hex(16)
        self.last_mobile = None
        self.network = getattr(args, "network", {"adapters": [], "firewall": {"state": "unverified"}, "selfCheck": "pending"})
        self.clients = {}
        self.tasks = []
        self.configure_lock = asyncio.Lock()
        self.solo_until = {}
        self.auth_failures = {}
        try:
            self.process = psutil.Process()
            self.process.cpu_percent()
        except psutil.Error:
            self.process = None
        self.cpu = None
        self.secure = None
        if getattr(args, "secure_mobile", False):
            identity = RelayIdentity(self.saved.get("relayPrivateKey"))
            self.saved["relayPrivateKey"] = identity.export_private()
            config = json.loads((ROOT / "network-relay-config.json").read_text(encoding="utf-8"))
            self.secure = SecureRelay(identity, self.run_id, config, self.musician_rpc)
        self.persist()

    def persist(self):
        tmp = self.file.with_suffix(".tmp")
        snapshot = json.loads(json.dumps(self.saved))
        for p in snapshot['profiles'].values():
            for c in p['mix']['channels']:
                c['solo'] = False
        tmp.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
        with contextlib.suppress(OSError):
            tmp.chmod(0o600)
        tmp.replace(self.file)

    def devices(self):
        return describe(sd, WINDOWS)

    def log(self, event, **details):
        self.events.append({'at': time.time(), 'event': event, **details})

    async def save(self):
        # Disk I/O is isolated from the audio/control event loop.
        async with self.save_lock:
            snapshot = json.loads(json.dumps(self.saved))
            await asyncio.to_thread(self.write_snapshot, snapshot)

    def write_snapshot(self, snapshot):
        for p in snapshot['profiles'].values():
            for c in p['mix']['channels']:
                c['solo'] = False
        tmp = self.file.with_suffix('.tmp')
        tmp.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding='utf-8')
        with contextlib.suppress(OSError):
            tmp.chmod(0o600)
        tmp.replace(self.file)

    def client_connected(self, c):
        return c['pcm'].connected() if c.get('engine') == 'native' else c['pc'].connectionState == 'connected'

    def resize_channels(self, count):
        if count == self.channel_count:
            return
        self.channel_count = count
        self.saved['channels'] = (self.saved['channels'] + [
            {'number': i+1, 'name': f'CH {i+1:02}', 'role': '', 'icon': ''}
            for i in range(len(self.saved['channels']), count)])[:count]
        for p in self.saved['profiles'].values():
            p['mix']['channels'] = (p['mix']['channels']+default_mix(count)['channels'])[:count]
            p['mix'] = clean_mix(p['mix'], count)
            p['allowed'] = (p['allowed']+[True]*count)[:count]
        self.peaks = np.zeros(count)
        self.rms = np.zeros(count)
        self.clip_at = np.zeros(count)

    def capture(self, data, frames, timing, status):
        started = time.perf_counter()
        # Audio callback: bounded copy only, no file/network I/O or asyncio per sample.
        if status:
            self.xruns += 1
        if len(self.raw) == self.raw.maxlen:
            self.capture_drops += 1
        self.raw.append((time.monotonic(), data.copy()))
        self.last_capture = time.monotonic()
        # Wake once per callback block rather than relying on Windows timer resolution.
        loop = self.capture_loop
        if loop is not None and not loop.is_closed() and not self.capture_wake_pending:
            self.capture_wake_pending = True
            try:
                loop.call_soon_threadsafe(self.wake_capture)
            except RuntimeError:
                self.capture_wake_pending = False

        self.callback_ms = (time.perf_counter()-started)*1000

    def wake_capture(self):
        self.capture_wake_pending = False
        if self.capture_event:
            self.capture_event.set()

    def open_device(self, device_id, buffer):
        if sd is None:
            raise ValueError("Capture indisponible : " + CAPTURE_IMPORT_ERROR)
        if self.stream:
            self.stream.close()
        self.stream = None
        self.device = None
        self.raw.clear()
        self.last_capture = 0
        self.peaks.fill(0)
        self.rms.fill(0)
        devices = self.devices()
        device = next((d for d in devices if d["id"] == device_id), None)
        if not device or (not self.alternative and device["inputs"] < CHANNELS):
            raise ValueError("Cette interface ne fournit pas 18 entrées simultanées")
        if WINDOWS and not device["asio"] and not (self.alternative and device.get("wasapi")):
            raise ValueError("MR18 détectée via " + device["driver"] + " : ASIO requis pour les 18 canaux séparés. Installe/active le pilote USB ASIO Midas.")
        self.wanted = {"name": device["name"], "driver": device["driver"]}
        self.buffer = buffer
        count = min(device['inputs'], 64) if self.alternative else CHANNELS
        self.resize_channels(count)
        self.startup = 'Opening ASIO' if device['asio'] else 'Opening alternative audio device'
        extra = sd.AsioSettings(channel_selectors=list(range(count))) if device['asio'] else None
        sd.check_input_settings(device=device_id, channels=count, samplerate=RATE, dtype="float32", extra_settings=extra)
        stream = sd.InputStream(device=device_id, channels=count, samplerate=RATE, blocksize=buffer,
                                latency="low", dtype="float32", extra_settings=extra, callback=self.capture)
        try:
            stream.start()
            if stream.channels != count or round(stream.samplerate) != RATE:
                raise ValueError("Le pilote n’a pas ouvert 18 entrées à 48 kHz")
        except Exception:
            stream.close()
            raise
        self.device = device
        self.stream = stream
        self.error = ""
        self.startup = 'Waiting for audio callback'

    def connected(self):
        return bool(self.stream and self.device and self.stream.active and
                    (not WINDOWS or self.device["asio"] or self.alternative) and self.stream.channels == self.channel_count and
                    round(self.stream.samplerate) == RATE and time.monotonic() - self.last_capture < 1)

    def profile(self, req, admin=False):
        token = req.headers.get("Authorization", "").removeprefix("Bearer ")
        if hmac.compare_digest(token, self.saved["admin"]):
            return None
        if not admin:
            for p in self.saved["profiles"].values():
                if hmac.compare_digest(token, p["token"]):
                    return p
        raise web.HTTPUnauthorized(text="Authentification requise")

    def public_profile(self, p):
        c = self.clients.get(p["id"])
        return {k: p.get(k) for k in ("id", "name", "role", "mix", "mixVersion", "allowed", "locked", "talkAllowed", "talkListen", "limits", "permissions")} | {
            "connected": bool(c and self.client_connected(c)),
            "device": c.get("device", "—") if c else "—", "network": c.get("metrics", {}).get("network", "Inconnu") if c else "—",
            "metrics": c.get("metrics", {}) if c else {}, "droppedFrames": c["track"].dropped if c and c.get("track") else 0,
            "levels": c["dsp"].telemetry if c and c.get("dsp") and self.connected() else None,
            "monitoringEngine": c.get('engine', 'opus') if c else None,
            "pcm": c['pcm'].status() if c and c.get('engine') in ('pcm', 'native') else None}

    def status(self):
        connected = self.connected()
        def db(v):
            return round(20 * np.log10(max(float(v), 1e-6)), 1)
        return {"service": "fosa-audio-bridge", "version": VERSION, "protocol": 1,
                "session": self.saved["session"], "experimental": True, "connected": connected,
                "device": self.device, "sampleRate": self.stream.samplerate if connected else None,
                "requestedRate": RATE, "buffer": self.stream.blocksize if connected else None,
                "requestedBuffer": self.buffer, "inputs": self.stream.channels if connected else 0,
                "mapping": [{"input": i+1, "channel": i+1, "verified": False} for i in range(self.channel_count)],
                "captureLatencyMs": round(self.stream.latency * 1000, 2) if connected else None,
                "audioLatencyMs": None, "latencyMethod": "Non mesurée : test physique nécessaire",
                "opusFrameMs": 20,
                "monitoringEngines": {"opus": {"packetMs": 20}, "pcm": {"packetMs": 5, "payloadMbps": 1.536, "experimental": True}},
                "cpu": self.cpu, "ramMB": round(self.process.memory_info().rss / 1048576, 1) if self.process else None,
                "xruns": self.xruns, "captureDrops": self.capture_drops,
                "audioCpu": self.audio_cpu, "callbackTimeMs": round(self.callback_ms, 3),
                "processingMs": round(self.processing_ms, 3), "panic": self.panic,
                "startup": 'FOSA LIVE READY' if connected else self.startup,
                "discovery": self.discovery.state, "nativePort": self.native_port, "channelCount": self.channel_count,
                "sampleRates": [48000], "bufferEnumeration": 'Unavailable: requested buffer validated on open',
                "supportedFeatures": ['monitorGain', 'limiter', 'panic', 'moreMe', 'nativeUDP'],
                "error": self.error, "clients": sum(self.client_connected(c) for c in self.clients.values()),
                "channels": [dict(c, rmsDb=db(self.rms[i]) if connected else None,
                                  peakDb=db(self.peaks[i]) if connected else None, active=connected,
                                  signal=bool(connected and self.rms[i] > 1e-4),
                                  clipping=bool(connected and time.monotonic()-self.clip_at[i] < 2))
                             for i, c in enumerate(self.saved["channels"])]}

    def remote_device(self, req):
        return bool(req.remote and not ipaddress.ip_address(req.remote).is_loopback and
                    req.remote not in [a["address"] for a in self.network["adapters"]])

    async def health(self, req):
        if self.remote_device(req):
            self.last_mobile = {"address": req.remote, "seenAt": time.time(), "stage": "Page accessible"}
        return json_response({"service": "fosa-audio-bridge", "protocol": 1, "version": VERSION, "runId": self.run_id})

    def require_local_console(self, req):
        # Loopback alone is insufficient: reject DNS rebinding and cross-origin browser requests.
        origin = req.headers.get("Origin")
        if (not req.remote or not ipaddress.ip_address(req.remote).is_loopback or
            req.url.host not in ("127.0.0.1", "localhost", "::1") or
            req.headers.get("X-FOSA-Console") != "1" or
            (origin and origin != f"{req.scheme}://{req.host}")):
            raise web.HTTPForbidden(text="Console automatique disponible uniquement sur le PC serveur")

    async def local_console(self, req):
        self.require_local_console(req)
        return json_response({"token": self.saved["admin"]})

    async def regisseur(self, req):
        self.profile(req, admin=True)
        self.require_local_console(req)
        pid = self.saved.get('regisseurProfile')
        p = self.saved['profiles'].get(pid)
        if p is None:
            pid = str(uuid.uuid4())
            mix = default_mix(self.channel_count)
            mix['master'] = 0  # The operator can speak without opening the PC monitor.
            p = {'id': pid, 'token': secrets.token_urlsafe(32), 'name': 'Régisseur PC',
                 'role': 'Régisseur', 'mix': mix, 'mixVersion': 0, 'allowed': [True]*self.channel_count,
                 'locked': False, 'talkAllowed': True, 'talkListen': True, 'localRegisseur': True}
            self.saved['profiles'][pid] = p
            self.saved['regisseurProfile'] = pid
            await self.save()
        return json_response({'token': p['token'], 'profile': self.public_profile(p),
                              'session': self.saved['session']})

    def join_url(self, role="Personnalisé", address=None, mode=None):
        invitation = {"session": self.saved["session"], "code": self.saved["joinCode"], "role": role}
        if getattr(self.args, 'low_latency', False) and mode != 'lan':
            invitation['engine'] = 'pcm'
        if self.secure and mode not in ("lan", "native"):
            return self.secure.config["clientUrl"] + "?musician=1#" + urlencode(invitation | self.secure.descriptor())
        base = self.args.public_url.rstrip("/")
        if address:
            if address not in [a["address"] for a in self.network["adapters"]]:
                raise ValueError("Cette adresse n’est pas une interface LAN détectée")
            u = urlsplit(base)
            base = f"{u.scheme}://{address}:{u.port or self.args.port}"
        # The session code belongs to the shareable QR, never the private console key.
        if mode == 'native':
            return 'fosa://bodypack?' + urlencode({'server': base, 'session': self.saved['session'], 'code': self.saved['joinCode']})
        return base + "/network.html?musician=1#" + urlencode(invitation)

    async def network_status(self, req):
        self.profile(req, admin=True)
        return json_response(self.network | {"joinCode": self.saved["joinCode"],
            "joinUrl": self.join_url(), "lanJoinUrl": self.join_url(mode="lan"), "lastClient": self.last_mobile,
            "secure": {"enabled": bool(self.secure), **(self.secure.status if self.secure else {})}})

    async def musician_rpc(self, message, client=None, pid=None):
        """The cloud and data channel expose musician actions only, never the console."""
        routes = {"health": self.health, "join": self.join, "state": self.get_state,
                  "meters": self.get_meters, "mix": self.mix, "offer": self.offer,
                  "control": self.control, "disconnect": self.disconnect,
                  "panic": self.panic_mute, "auto-level": self.auto_level, "more-me": self.more_me}
        try:
            if not isinstance(message, dict) or message.get("path") not in routes:
                raise web.HTTPForbidden(text="Commande indisponible sur le lien musicien")
            path = message["path"]
            if path in ("join", "mix", "offer", "control", "disconnect", "panic", "more-me") and not isinstance(message.get("body"), dict):
                raise ValueError("Commande musicien invalide")
            if client is not None:
                if self.clients.get(pid) is not client or path in ("join", "offer"):
                    raise web.HTTPForbidden(text="Liaison musicien invalide")
                token = self.saved["profiles"][pid]["token"]
            else:
                token = message.get("token", "")
            if not isinstance(token, str) or hmac.compare_digest(token, self.saved["admin"]):
                raise web.HTTPForbidden(text="La console régisseur reste sur le PC serveur")
            request = MusicianRequest(token, message.get("body"))
            if path == "control":
                p = self.profile(request)
                current = self.clients.get(p["id"])
                # Never reopen TALK from a delayed cloud message after a direct release.
                if client is None and current and current.get("dataChannel"):
                    raise web.HTTPConflict(text="TALK utilise la liaison locale directe")
            response = await routes[path](request)
            if client is None and path == "join" and response.status == 200:
                self.last_mobile = {"address": "Page HTTPS", "seenAt": time.time(), "stage": "Profil rejoint"}
            return {"status": response.status, "data": json.loads(response.text)}
        except web.HTTPException as error:
            return {"status": error.status, "data": {"error": error.text}}
        except CAPTURE_ERRORS as error:
            return {"status": 400, "data": {"error": str(error)}}

    async def join(self, req):
        b = await req.json()
        now = time.monotonic()
        attempts = [t for t in self.auth_failures.get(req.remote, []) if now-t < 60]
        if len(attempts) >= 10:
            raise web.HTTPTooManyRequests(text="Patientez une minute")
        if not hmac.compare_digest(str(b.get("code", "")), self.saved["joinCode"]):
            self.auth_failures[req.remote] = attempts + [now]
            raise web.HTTPUnauthorized(text="Code session incorrect")
        if b.get("session") and b["session"] != self.saved["session"]:
            raise web.HTTPConflict(text="Le QR correspond à une autre session. Scanne le QR actuel du PC.")
        if len(self.saved["profiles"]) >= 64:
            raise web.HTTPServiceUnavailable(text="Limite de 64 profils atteinte")
        pid = str(uuid.uuid4())
        p = {"id": pid, "token": secrets.token_urlsafe(32), "name": str(b.get("name", "Musicien"))[:60],
             "role": str(b.get("role", "Personnalisé"))[:40], "mix": default_mix(self.channel_count),
             "mixVersion": 0, "allowed": [True]*self.channel_count, "locked": False, "talkAllowed": False, "talkListen": True}
        self.saved["profiles"][pid] = p
        await self.save()
        if self.remote_device(req):
            self.last_mobile = {"address": req.remote, "seenAt": time.time(), "stage": "Profil rejoint"}
        return json_response({"token": p["token"], "profile": self.public_profile(p), "session": self.saved["session"]})

    async def get_state(self, req):
        p = self.profile(req)
        result = self.status()
        result["profile"] = self.public_profile(p) if p else None
        result["admin"] = p is None
        if p is None:
            result["users"] = [self.public_profile(v) for v in self.saved["profiles"].values()]
        result["talkTargets"] = [{"id": v["id"], "name": v["name"], "role": v["role"]}
                                 for v in self.saved["profiles"].values() if v["talkListen"] and
                                 v["id"] != (p or {}).get("id") and v["id"] in self.clients]
        return json_response(result)

    async def list_devices(self, req):
        self.profile(req, admin=True)
        return json_response({"devices": self.devices()})

    async def get_meters(self, req):
        p = self.profile(req)
        connected = self.connected()
        def db(v):
            return round(20 * np.log10(max(float(v), 1e-6)), 1)
        channels = [{
            "active": bool(connected),
            "rmsDb": db(self.rms[i]) if connected else None,
            "peakDb": db(self.peaks[i]) if connected else None,
            "signal": bool(connected and self.rms[i] > 1e-4),
            "clipping": bool(connected and time.monotonic() - self.clip_at[i] < 2),
        } for i in range(self.channel_count)]
        c = self.clients.get(p['id']) if p else None
        return json_response({'connected': bool(connected), 'channels': channels,
            'levels': c['dsp'].telemetry if connected and c and c.get('dsp') else None})

    async def configure(self, req):
        self.profile(req, admin=True)
        b = await req.json()
        if any(self.client_connected(c) for c in self.clients.values()):
            raise web.HTTPConflict(text="Arrêtez les écoutes avant de modifier l’interface ou le buffer")
        buffer = int(b.get("buffer", 128))
        self.alternative = b.get("alternative") is True
        if buffer not in (64, 128, 256, 512, 1024):
            raise ValueError("Buffer non pris en charge")
        async with self.configure_lock:
            try:
                await asyncio.to_thread(self.open_device, int(b["device"]), buffer)
            except Exception as e:
                self.error = str(e)
                raise ValueError("Capture : " + str(e)) from e
        return json_response(self.status())

    async def channels(self, req):
        self.profile(req, admin=True)
        b = await req.json()
        i = int(b["number"])-1
        if not 0 <= i < self.channel_count:
            raise ValueError("Canal invalide")
        for key in ("name", "role", "icon", "group"):
            if key in b:
                self.saved["channels"][i][key] = str(b[key])[:60]
        if 'disabled' in b:
            self.saved['channels'][i]['disabled'] = b['disabled'] is True
        if 'order' in b:
            self.saved['channels'][i]['order'] = int(number(b['order'], i, 0, self.channel_count-1))
        await self.save()
        return json_response({"ok": True})

    async def mix(self, req):
        caller = self.profile(req)
        b = await req.json()
        p = caller or self.saved["profiles"].get(b.get("id"))
        if not p:
            raise web.HTTPNotFound()
        if caller and p["locked"]:
            raise web.HTTPForbidden(text="Mix verrouillé par le régisseur")
        proposed = clean_mix(b['mix'], self.channel_count)
        limits = p.get('limits', {})
        proposed['monitorGainDb'] = min(proposed['monitorGainDb'], limits.get('gainMaxDb', 12))
        proposed['master'] = min(proposed['master'], limits.get('masterMax', 1))
        if caller:
            permissions = p.get('permissions', {})
            for i, row in enumerate(proposed['channels']):
                for key in ('gain', 'pan', 'solo', 'mute'):
                    if not p['allowed'][i] or permissions.get(key) is False:
                        row[key] = p['mix']['channels'][i][key]
        p['mix'] = proposed
        self.solo_until[p["id"]] = time.monotonic()+1.5
        p["mixVersion"] = p.get("mixVersion", 0)+1
        await self.save()
        return json_response({"mix": p["mix"], "mixVersion": p["mixVersion"]})

    async def matrix(self, req):
        self.profile(req, admin=True)
        b = await req.json()
        p = self.saved["profiles"].get(b.get("id"))
        if not p:
            raise web.HTTPNotFound()
        if isinstance(b.get('limits'), dict):
            old = p.get('limits', {})
            p['limits'] = {key: number(b['limits'].get(key), old.get(key, maximum), 0, maximum)
                          for key, maximum in [('gainMaxDb', 12), ('masterMax', 1), ('moreMeMaxDb', 6)]}
            p['mix']['monitorGainDb'] = min(p['mix'].get('monitorGainDb', 0), p['limits']['gainMaxDb'])
            p['mix']['master'] = min(p['mix']['master'], p['limits']['masterMax'])
        if isinstance(b.get('permissions'), dict):
            p['permissions'] = {k: b['permissions'].get(k) is not False for k in ('gain', 'pan', 'mute', 'solo')}
        for key in ("locked", "talkAllowed", "talkListen"):
            if key in b:
                p[key] = b[key] is True
        if not p["talkAllowed"] and p["id"] in self.clients:
            self.clients[p["id"]]["talkUntil"] = 0
        if "allowed" in b:
            if not isinstance(b["allowed"], list) or len(b["allowed"]) != self.channel_count:
                raise ValueError(f"{self.channel_count} assignations requises")
            p["allowed"] = [v is True for v in b["allowed"]]
        await self.save()
        return json_response(self.public_profile(p))

    async def close_client(self, pid):
        c = self.clients.pop(pid, None)
        if c:
            if c.get("track"):
                c["track"].stop()
            for task in c["tasks"]:
                task.cancel()
            if c.get('pc'):
                await c['pc'].close()
            if c.get('engine') == 'native':
                c['pcm'].close()
            self.log('client_disconnected', id=pid)

    async def offer(self, req):
        p = self.profile(req)
        if p is None:
            raise web.HTTPForbidden(text="Rejoignez avec un profil musicien pour écouter")
        b = await req.json()
        engine = b.get('monitoringEngine', 'opus')
        if engine not in ('opus', 'pcm'):
            raise ValueError('Moteur monitoring inconnu')
        await self.close_client(p["id"])
        pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        track = StereoTrack()
        client = {"pc": pc, "track": track, "tasks": [], "dsp": MonitorEngine(self.channel_count), "previous": None, "limiter": 1., "metrics": {},
                  "voice": deque(maxlen=3), "voiceAt": 0, "talkUntil": 0,
                  "dataChannel": None, "controlSeq": -1,
                  "engine": engine, "pcm": PcmSender(), "opusPending": [],
                  "voiceCurrent": None, "voiceOffset": 0,
                  "created": time.monotonic(), "target": "all", "device": str(b.get("device", "Navigateur"))[:100]}
        self.clients[p["id"]] = client
        self.log("client_connected", id=p["id"], engine=engine)
        @pc.on("datachannel")
        def data_channel(channel):
            if channel.label == 'fosa-pcm-v1' and engine == 'pcm' and client['pcm'].channel is None:
                if channel.ordered or channel.maxRetransmits != 0:
                    channel.close()
                    return
                client['pcm'].channel = channel
                return
            if channel.label != "fosa-control" or client["dataChannel"] is not None:
                channel.close()
                return
            client["dataChannel"] = channel
            queue = asyncio.Queue(maxsize=32)
            async def respond():
                while True:
                    message = await queue.get()
                    result = await self.musician_rpc(message, client, p["id"])
                    if channel.readyState == "open":
                        channel.send(_json_dumps(dict(result, id=message.get("id"))))
            task = asyncio.create_task(respond())
            client["tasks"].append(task)
            @channel.on("message")
            def receive(data):
                if not isinstance(data, str) or len(data) > 96*1024 or queue.full():
                    return
                try:
                    message = json.loads(data)
                    if isinstance(message, dict) and isinstance(message.get("id"), str):
                        queue.put_nowait(message)
                except ValueError:
                    return
            @channel.on("close")
            def closed():
                client["talkUntil"] = 0
                task.cancel()
        @pc.on("track")
        def incoming(t):
            if t.kind == "audio":
                task = asyncio.create_task(self.receive_voice(t, client))
                client["tasks"].append(task)
        @pc.on("connectionstatechange")
        async def changed():
            if pc.connectionState == "failed" and self.clients.get(p["id"]) is client:
                await self.close_client(p["id"])
        try:
            await pc.setRemoteDescription(RTCSessionDescription(sdp=b["sdp"], type="offer"))
            if engine == 'opus':
                pc.addTrack(track)
            opus = [c for c in RTCRtpSender.getCapabilities("audio").codecs if c.mimeType.lower() == "audio/opus"]
            for t in pc.getTransceivers():
                if t.kind == "audio":
                    t.setCodecPreferences(opus)
            await pc.setLocalDescription(await pc.createAnswer())
            return json_response({"type": "answer", "sdp": pc.localDescription.sdp} |
                                 ({"monitoringEngine": engine} if engine == 'pcm' else {}))
        except Exception:
            await self.close_client(p["id"])
            raise

    async def receive_voice(self, track, c):
        resampler = AudioResampler(format="fltp", layout="stereo", rate=RATE, frame_size=FRAME)
        try:
            while True:
                f = await track.recv()
                for part in resampler.resample(f):
                    samples = part.to_ndarray().T.copy()
                    if samples.shape == (FRAME, 2):
                        c["voice"].append(samples)
                        c["voiceAt"] = time.monotonic()
        except (asyncio.CancelledError, Exception):
            return

    async def control(self, req):
        p = self.profile(req)
        if p is None:
            raise web.HTTPForbidden()
        b = await req.json()
        self.solo_until[p["id"]] = time.monotonic()+1.5
        c = self.clients.get(p["id"])
        if c:
            sequence = b.get("sequence")
            if sequence is not None and (not isinstance(sequence, int) or sequence <= c.get("controlSeq", -1)):
                return json_response({"ok": True, "talkAllowed": p["talkAllowed"], "talkActive": c["talkUntil"] > time.monotonic()})
            if sequence is not None:
                c["controlSeq"] = sequence
            c["talkUntil"] = time.monotonic()+1.5 if p["talkAllowed"] and b.get("talk") is True else 0
            c["target"] = str(b.get("target", "all"))[:80]
            source = b.get('talkSource')
            c['talkSource'] = int(source) if p.get('localRegisseur') and isinstance(source, int) and 0 <= source < self.channel_count else None
            m = b.get("metrics")
            if isinstance(m, dict):
                c["metrics"] = {k: m.get(k) for k in ("rtt", "jitter", "loss", "network", "audioLatency", "playback", "packets", "pcm", "battery", "output", "buffer", "underruns")}
        return json_response({"ok": True, "talkAllowed": p["talkAllowed"],
                              "talkActive": bool(c and c["talkUntil"] > time.monotonic())})

    async def disconnect(self, req):
        p = self.profile(req)
        if p:
            await self.close_client(p["id"])
        return json_response({"ok": True})

    async def panic_mute(self, req):
        p = self.profile(req)
        b = await req.json()
        if p is None:
            self.panic = b.get('active') is not False
            self.saved["panic"] = self.panic
            self.log('panic_all', active=self.panic)
        else:
            # Safety mute always permitted, including locked profiles.
            active = b.get('active') is not False
            if not active and p['locked']:
                raise web.HTTPForbidden(text='Mix verrouillé : le régisseur doit rétablir la sortie')
            p['mix'] = dict(p['mix'], muteAll=active)
            p['mixVersion'] = p.get('mixVersion', 0)+1
            self.log('panic_personal', id=p['id'], active=active)
        await self.save()
        return json_response({'active': self.panic if p is None else p['mix']['muteAll']})

    async def auto_level(self, req):
        p = self.profile(req)
        if p is None:
            raise web.HTTPForbidden(text='Utilisez un profil musicien')
        c = self.clients.get(p['id'])
        result = await asyncio.get_running_loop().run_in_executor(self.dsp_pool, c['dsp'].recommend, p.get('limits', {}).get('gainMaxDb', 12)) if c and c.get('dsp') and self.connected() else {
            'available': False, 'reason': 'Capture et écoute actives requises'}
        return json_response(result)

    async def more_me(self, req):
        p = self.profile(req)
        if not p or p['locked'] or p.get('permissions', {}).get('gain') is False:
            raise web.HTTPForbidden(text='MORE ME verrouillé')
        i = p['mix'].get('mainChannel', 0)
        if not p['allowed'][i]:
            raise web.HTTPForbidden(text='Canal principal non autorisé')
        b = await req.json()
        delta = number(b.get('db'), 3, 0, p.get('limits', {}).get('moreMeMaxDb', 6))
        mix = clean_mix(p['mix'], self.channel_count)
        mix['channels'][i]['gain'] = min(1., mix['channels'][i]['gain']*10**(delta/20))
        p['mix'] = mix
        p['mixVersion'] = p.get('mixVersion', 0)+1
        await self.save()
        return json_response({'mix': mix, 'mixVersion': p['mixVersion']})

    async def presets(self, req):
        self.profile(req, admin=True)
        presets = self.saved.setdefault('presets', {})
        if req.method == 'GET':
            return json_response({'presets': list(presets)})
        b = await req.json()
        name = str(b.get('name', '')).strip()[:60]
        if not name:
            raise ValueError('Nom du preset requis')
        if b.get('action') == 'save':
            if name not in presets and len(presets) >= 32:
                raise ValueError('Limite de 32 presets')
            presets[name] = json.loads(json.dumps({'channels': self.saved['channels'],
                'profiles': {pid: {k: p[k] for k in ('name', 'role', 'mix', 'allowed', 'locked', 'talkAllowed', 'talkListen', 'limits', 'permissions') if k in p}
                             for pid, p in self.saved['profiles'].items()}}))
        elif b.get('action') == 'load':
            if any(self.client_connected(c) for c in self.clients.values()):
                raise web.HTTPConflict(text='Arrêtez les écoutes avant de rappeler une session')
            preset = presets.get(name)
            if not preset or len(preset['channels']) != self.channel_count:
                raise ValueError('Preset absent ou nombre d’entrées différent')
            self.saved['channels'] = json.loads(json.dumps(preset['channels']))
            for pid, value in preset['profiles'].items():
                if pid in self.saved['profiles']:
                    self.saved['profiles'][pid].update(json.loads(json.dumps(value)))
                    self.saved['profiles'][pid]['mix'] = clean_mix(value['mix'], self.channel_count)
                    for c in self.saved['profiles'][pid]['mix']['channels']:
                        c['solo'] = False
            self.log('preset_loaded', name=name)
        else:
            raise ValueError('Action preset inconnue')
        await self.save()
        return json_response({'presets': list(presets)})

    async def native_session(self, req):
        p = self.profile(req)
        if p is None:
            raise web.HTTPForbidden(text='Profil bodypack requis')
        if not self.native_port:
            raise web.HTTPServiceUnavailable(text='Transport UDP indisponible')
        await self.close_client(p['id'])
        c = {'pc': None, 'track': None, 'engine': 'native', 'tasks': [], 'metrics': {},
             'dsp': MonitorEngine(self.channel_count), 'voice': deque(maxlen=4), 'voiceAt': 0,
             'talkUntil': 0, 'voiceCurrent': None, 'voiceOffset': 0, 'controlSeq': -1,
             'target': 'all', 'created': time.monotonic(), 'device': 'Android native bodypack'}
        sender = NativeSender(self.native, c)
        c['pcm'] = sender
        self.clients[p['id']] = c
        self.log('client_connected', id=p['id'], engine='native')
        return json_response({'port': self.native_port, 'streamId': sender.id.hex(),
            'receiveKey': sender.output_key.hex(), 'sendKey': sender.input_key.hex(),
            'sampleRate': RATE, 'packetMs': 5, 'profile': self.public_profile(p)})

    async def logs(self, req):
        self.profile(req, admin=True)
        return json_response({'version': VERSION, 'events': list(self.events), 'status': self.status()})

    async def qr(self, req):
        self.profile(req, admin=True)
        role = req.query.get("role", "Personnalisé")[:40]
        url = self.join_url(role, req.query.get("address"), req.query.get("mode"))
        out = io.BytesIO()
        qrcode.make(url, image_factory=SvgPathImage).save(out)
        return web.Response(body=out.getvalue(), content_type="image/svg+xml")

    def render_block(self, block, now):
        outputs = []
        safe = np.clip(np.nan_to_num(block, nan=0., posinf=0., neginf=0.), -8, 8)
        self.peaks = np.maximum(np.max(np.abs(safe), axis=0), self.peaks*.995)
        self.rms = np.sqrt(np.mean(safe*safe, axis=0))
        self.clip_at[np.max(np.abs(block), axis=0) >= .999] = now
        voices = {}
        for pid, c in list(self.clients.items()):
            if c.get('talkSource') is not None and c['talkUntil'] > now and self.saved['profiles'][pid]['talkAllowed']:
                voices[pid] = np.repeat(block[:, c['talkSource']:c['talkSource']+1], 2, axis=1)
                continue
            if c.get('voiceCurrent') is None and c['voice']:
                c['voiceCurrent'] = c['voice'].popleft()
                c['voiceOffset'] = 0
            if c.get('voiceCurrent') is not None:
                offset = c['voiceOffset']
                voice = c['voiceCurrent'][offset:offset+PCM_FRAME]
                c['voiceOffset'] += PCM_FRAME
                if c['voiceOffset'] >= len(c['voiceCurrent']):
                    c['voiceCurrent'] = None
                if self.saved["profiles"][pid]["talkAllowed"] and c["talkUntil"] > now and now-c["voiceAt"] < .15:
                    voices[pid] = voice
        for pid, c in list(self.clients.items()):
            p = self.saved["profiles"][pid]
            if self.solo_until.get(pid, 0) < now and any(ch['solo'] for ch in p['mix']['channels']):
                p["mix"] = dict(p["mix"], channels=[dict(ch, solo=False) for ch in p["mix"]["channels"]])
            heard = [v for other, v in voices.items() if other != pid and p["talkListen"] and
                     self.clients.get(other, {}).get('target') in ("all", pid, "role:"+p["role"])]
            voice = sum(heard) if heard else None
            dsp = c.get('dsp')
            if dsp is None or len(dsp.previous) != self.channel_count:
                dsp = c['dsp'] = MonitorEngine(self.channel_count)
            allowed = [p['allowed'][i] and not self.saved['channels'][i].get('disabled', False)
                       for i in range(self.channel_count)]
            output = dsp.process(block, p['mix'], allowed, voice, self.panic)
            outputs.append((c, output))
        return outputs

    async def pump(self):
        self.capture_loop = asyncio.get_running_loop()
        self.capture_event = asyncio.Event()
        pending = np.empty((0, self.channel_count), dtype=np.float32)
        pending_at = None
        next_silence = time.monotonic()
        while True:
            self.capture_event.clear()
            blocks = []
            now = time.monotonic()
            while self.raw:
                ts, block = self.raw.popleft()
                budget = max(.02, self.buffer/RATE*1.5) if any(c.get('engine') in ('pcm', 'native') for c in self.clients.values()) else .08
                if now-ts > budget or (pending_at is not None and now-pending_at > budget):
                    self.capture_drops += 1
                    pending = np.empty((0, self.channel_count), dtype=np.float32)
                    pending_at = None
                    for client in self.clients.values():
                        client.get('opusPending', []).clear()
                    continue
                if pending_at is None:
                    pending_at = ts
                if pending.shape[1] != block.shape[1]:
                    pending = np.empty((0, block.shape[1]), dtype=np.float32)
                pending = np.concatenate((pending, block))
                while len(pending) >= PCM_FRAME:
                    blocks.append((pending_at, pending[:PCM_FRAME]))
                    pending = pending[PCM_FRAME:]
                    pending_at = ts if len(pending) else None
            if not self.connected() and now >= next_silence:
                # Transport keepalive silence, explicitly reported DISCONNECTED in telemetry.
                blocks = [(now, np.zeros((PCM_FRAME, self.channel_count), dtype=np.float32))]
                pending = np.empty((0, self.channel_count), dtype=np.float32)
                pending_at = None
                next_silence = now+.005
            for captured_at, block in blocks:
                started = time.perf_counter()
                outputs = await self.capture_loop.run_in_executor(self.dsp_pool, self.render_block, block, now)
                self.processing_ms = (time.perf_counter()-started)*1000
                load = self.processing_ms/5*100
                self.audio_cpu = load if self.audio_cpu is None else .95*self.audio_cpu+.05*load
                for c, output in outputs:
                    if c.get('engine') in ('pcm', 'native'):
                        c['pcm'].feed(output, (time.monotonic()-captured_at)*1000)
                    else:
                        c.setdefault('opusPending', []).append(output)
                        if len(c['opusPending']) == FRAME//PCM_FRAME:
                            c['track'].feed(np.concatenate(c['opusPending']))
                            c['opusPending'].clear()
            try:
                await asyncio.wait_for(self.capture_event.wait(), .005)
            except asyncio.TimeoutError:
                pass  # Source-absent keepalive; valid capture wakes the loop directly.

    async def monitor(self):
        while True:
            if self.tasks and self.tasks[0].done() and not self.tasks[0].cancelled():
                self.error = 'Audio worker restarted: ' + str(self.tasks[0].exception())
                self.log('audio_worker_restart', error=self.error)
                self.raw.clear()
                for c in list(self.clients.values()):
                    c['dsp'] = MonitorEngine(self.channel_count)
                self.tasks[0] = asyncio.create_task(self.pump())
            self.cpu = self.process.cpu_percent() if self.process else None
            now = time.monotonic()
            if sd is not None and not self.connected() and now-self.last_retry > 4:
                self.last_retry = now
                try:
                    async with self.configure_lock:
                        if self.stream:
                            await asyncio.to_thread(self.stream.close)
                            self.stream = None
                        # Re-enumerate after hotplug only with capture stopped.
                        await asyncio.to_thread(sd._terminate)
                        await asyncio.to_thread(sd._initialize)
                        devices = self.devices()
                        candidates = [d for d in devices if d["inputs"] >= (1 if self.alternative else CHANNELS) and d.get("usable", True) and
                                      ((self.wanted and d["name"] == self.wanted["name"] and d["driver"] == self.wanted["driver"]) or
                                       (not self.wanted and (d["mr18"] or d["midasUsb"])))]
                        candidates.sort(key=lambda d: d.get("priority", 0 if d["asio"] else 4))
                        if candidates:
                            await asyncio.to_thread(self.open_device, candidates[0]["id"], self.buffer)
                        else:
                            if not self.wanted and any(d["mr18"] or d["midasUsb"] for d in devices):
                                self.error = "Midas détectée, mais aucun pilote ASIO avec 18 entrées disponible. Vérifie le pilote Midas 64 bits."
                            else:
                                self.error = "MR18 non connectée" if not self.wanted else "Interface déconnectée — reconnexion en cours"
                except Exception as e:
                    self.error = str(e)
            for pid, c in list(self.clients.items()):
                if (c.get("pc") and c["pc"].connectionState in ("closed", "failed")) or (not self.client_connected(c) and now-c["created"] > 30):
                    await self.close_client(pid)
            await asyncio.sleep(1)

    async def start(self, app):
        try:
            await self.discovery.start([a['address'] for a in self.network['adapters']], getattr(self.args, 'port', 8765))
        except Exception as e:
            self.log('discovery_unavailable', error=str(e))
        try:
            transport, _ = await asyncio.get_running_loop().create_datagram_endpoint(
                lambda: self.native, local_addr=(getattr(self.args, 'host', '0.0.0.0'), getattr(self.args, 'port', 8765)))
            self.native_port = transport.get_extra_info('sockname')[1]
        except OSError as e:
            self.log('native_udp_unavailable', error=str(e))
        self.tasks = [asyncio.create_task(self.pump()), asyncio.create_task(self.monitor())]
        if self.secure:
            self.tasks.append(asyncio.create_task(self.secure.run()))
        if getattr(self.args, "musicians", False) or getattr(self.args, "open_browser", False):
            self.tasks.append(asyncio.create_task(self.ready()))

    async def ready(self):
        """Wait for actual HTTP binding before showing the console; this isn't a remote probe."""
        port = self.args.port
        async with ClientSession() as client:
            for _ in range(40):
                try:
                    url = self.args.public_url.rstrip("/") + "/api/health"
                    async with client.get(url, timeout=1) as response:
                        health = await response.json()
                        if response.status != 200 or health.get("runId") != self.run_id:
                            raise ValueError("Un autre service occupe ce port")
                    self.network["selfCheck"] = "passed"
                    self.network["selfCheckNote"] = "HTTP vérifié depuis ce PC. Accès depuis un téléphone en attente."
                    if getattr(self.args, "open_browser", False):
                        url = f"http://127.0.0.1:{port}/network.html?console=1"
                        if getattr(self.args, 'native_bodypack', False):
                            url += '&bodypack=1'
                        if getattr(self.args, 'regisseur', False):
                            url += '&regisseur=1&view=live'
                            if getattr(self.args, 'low_latency', False):
                                url += '&engine=pcm'
                        await asyncio.to_thread(webbrowser.open, url)
                    return
                except (OSError, ValueError, asyncio.TimeoutError):
                    await asyncio.sleep(.25)
        self.network["selfCheck"] = "failed"
        self.network["selfCheckNote"] = "Le PC n’arrive pas à joindre le bridge sur l’adresse LAN sélectionnée."
    async def stop(self, app):
        if self.secure:
            await self.secure.stop()
        for t in self.tasks:
            t.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        for pid in list(self.clients):
            await self.close_client(pid)
        if self.stream:
            self.stream.close()
        if self.native.transport:
            self.native.transport.close()
        await self.discovery.close()
        self.dsp_pool.shutdown(wait=True)

    def app(self):
        @web.middleware
        async def errors(req, handler):
            origin = req.headers.get("Origin")
            allowed = {self.args.public_url.rstrip("/"), "https://zonampoina.github.io", *self.args.allow_origin}
            # Same-origin requests and explicitly allowed app origins only.
            if origin and origin not in allowed and origin != f"{req.scheme}://{req.host}":
                raise web.HTTPForbidden(text="Origine non autorisée")
            if req.method == "OPTIONS":
                response = web.Response()
            else:
                try:
                    response = await handler(req)
                except web.HTTPException as e:
                    response = json_response({"error": e.text}, status=e.status)
                except CAPTURE_ERRORS as e:
                    response = json_response({"error": str(e)}, status=400)
            if origin:
                response.headers.update({"Access-Control-Allow-Origin": origin, "Vary": "Origin",
                    "Access-Control-Allow-Headers": "Authorization, Content-Type",
                    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
                    "Access-Control-Allow-Private-Network": "true"})
            response.headers["Cache-Control"] = "no-store" if req.path.startswith("/api") else "no-cache"
            response.headers["X-Content-Type-Options"] = "nosniff"
            response.headers["Referrer-Policy"] = "no-referrer"
            return response
        app = web.Application(middlewares=[errors], client_max_size=128*1024)
        app.add_routes([web.get('/api/health', self.health), web.post('/api/join', self.join),
                        web.get('/api/state', self.get_state), web.get('/api/meters', self.get_meters), web.get('/api/devices', self.list_devices),
                        web.post('/api/configure', self.configure), web.post('/api/channel', self.channels),
                        web.post('/api/mix', self.mix), web.post('/api/panic', self.panic_mute),
                        web.get('/api/auto-level', self.auto_level), web.post('/api/more-me', self.more_me),
                        web.post('/api/native', self.native_session), web.get('/api/presets', self.presets), web.post('/api/presets', self.presets), web.get('/api/logs', self.logs), web.post('/api/matrix', self.matrix),
                        web.post('/api/offer', self.offer), web.post('/api/control', self.control),
                        web.post('/api/disconnect', self.disconnect), web.get('/api/qr', self.qr)])
        app.add_routes([web.post('/api/local-console', self.local_console),
                        web.post('/api/regisseur', self.regisseur), web.get('/api/network', self.network_status)])
        # Never expose state.json, admin keys, source tree or arbitrary filesystem paths.
        files = {'/': 'network.html', '/network.html': 'network.html', '/network.js': 'network.js',
                 '/network.css': 'network.css', '/bodypack.css': 'bodypack.css', '/fosa-icon.svg': 'fosa-icon.svg',
                 '/low-latency-worklet.js': 'low-latency-worklet.js',
                 '/low-latency.js': 'low-latency.js',
                 '/network-relay.js': 'network-relay.js', '/network-relay-config.json': 'network-relay-config.json'}
        async def static(req):
            name = files.get(req.path)
            if not name:
                raise web.HTTPNotFound()
            return web.FileResponse(ROOT/name)
        app.router.add_get('/{tail:.*}', static)
        app.on_startup.append(self.start)
        app.on_cleanup.append(self.stop)
        return app

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='FOSA Audio Bridge')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', default=8765, type=int)
    parser.add_argument('--cert')
    parser.add_argument('--key')
    parser.add_argument('--public-url', default='http://127.0.0.1:8765')
    parser.add_argument('--allow-origin', action='append', default=[])
    parser.add_argument('--insecure-lan', action='store_true', help='HTTP LAN mode for easy listen-only mobile tests')
    parser.add_argument('--musicians', action='store_true', help='Automatic LAN listen-only startup, QR and Windows firewall')
    parser.add_argument('--secure-mobile', action='store_true', help='Trusted HTTPS musician page and encrypted connection setup for mobile talkback')
    parser.add_argument('--low-latency', action='store_true', help='PCM 5 ms suggested in secure QR; initial capture buffer 128')
    parser.add_argument('--native-bodypack', action='store_true', help='Show offline Android native QR at startup')
    parser.add_argument('--regisseur', action='store_true', help='Open the local operator LIVE view with private PC audio profile')
    parser.add_argument('--open-browser', action='store_true', help='Open the local console only after the server is ready')
    parser.add_argument('--data-dir', default=str(Path.home()/'.fosa-audio'))
    args = parser.parse_args()
    if args.musicians:
        args.host = '0.0.0.0'
        args.insecure_lan = True
        print('FOSA · recherche du réseau Wi-Fi/Ethernet du PC…', flush=True)
        try:
            adapters = discover()
        except Exception as e:
            parser.exit(1, f'Diagnostic réseau indisponible : {e}\n')
        if not adapters:
            parser.exit(1, 'Aucun réseau Wi-Fi/Ethernet IPv4 local détecté. Connecte ce PC au réseau des musiciens.\n')
        args.public_url = f'http://{adapters[0]["address"]}:{args.port}'
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind((args.host, args.port))
            except OSError:
                parser.exit(1, f'Le port {args.port} est déjà occupé. Ferme le précédent bridge FOSA puis relance.\n')
        print('DÉMARRER FOSA POUR LES MUSICIENS', flush=True)
        print('Vérification du pare-feu ; Windows peut demander une autorisation au premier lancement.', flush=True)
        firewall = configure_firewall(args.port, [a['address'] for a in adapters])
        args.network = {"adapters": adapters, "firewall": firewall, "selfCheck": "pending"}
        print(firewall['message'], flush=True)
    if args.host not in ('127.0.0.1', 'localhost', '::1') and not (args.cert and args.key) and not args.insecure_lan:
        parser.error('Le mode LAN exige --cert et --key (HTTPS), ou --insecure-lan pour le mode mobile rapide écoute seule.')
    bridge = Bridge(args)
    print(f'FOSA Audio Bridge {VERSION}\nConsole : {args.public_url}/network.html')
    if sd is not None:
        try:
            hostapis = [h["name"] for h in sd.query_hostapis()]
            mr18s = [d for d in bridge.devices() if d["mr18"]]
            print("Pilotes audio : " + (", ".join(hostapis) if hostapis else "aucun"))
            if mr18s:
                for d in mr18s:
                    flag = "OK ASIO" if d["asio"] else "NON COMPATIBLE 18CH (ASIO requis)"
                    print(f'MR18 : {d["name"]} · {d["driver"]} · {d["inputs"]} IN · {flag}')
            else:
                print("MR18 : aucune interface audio détectée")
        except Exception as e:
            print("Diagnostic audio indisponible :", e)
    print(f'Code musiciens : {bridge.saved["joinCode"]}\nClé régisseur (privée) : {bridge.saved["admin"]}')
    context = None
    if args.cert and args.key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.cert, args.key)
    web.run_app(bridge.app(), host=args.host, port=args.port, ssl_context=context, access_log=None)
