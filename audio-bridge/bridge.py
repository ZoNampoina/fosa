"""FOSA Audio Bridge, experimental native ASIO capture and local WebRTC server."""
import os
os.environ.setdefault("SD_ENABLE_ASIO", "1")  # Must precede sounddevice import.
import argparse
import asyncio
import contextlib
from collections import deque
from fractions import Fraction
import hmac
import io
import json
from pathlib import Path
import secrets
import socket
import ssl
import time
import uuid

import numpy as np
import psutil
try:
    import sounddevice as sd
    CAPTURE_IMPORT_ERROR = ''
except (ImportError, OSError) as e:
    sd = None
    CAPTURE_IMPORT_ERROR = str(e)
from aiohttp import web
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, MediaStreamTrack, RTCRtpSender
from av import AudioFrame, AudioResampler
import qrcode
from qrcode.image.svg import SvgPathImage
from mixer import CHANNELS, RATE, FRAME, clean_mix, default_mix, render_mix
CAPTURE_ERRORS = (ValueError, KeyError, TypeError, OSError) + ((sd.PortAudioError,) if sd else ())

ROOT = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"
VERSION = "0.9.2-asiofix"

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
        self.stream = None
        self.device = None
        self.wanted = None
        self.buffer = 256
        self.raw = deque(maxlen=32)
        self.last_capture = 0
        self.last_retry = 0
        self.peaks = np.zeros(CHANNELS)
        self.rms = np.zeros(CHANNELS)
        self.clip_at = np.zeros(CHANNELS)
        self.error = "Capture indisponible : " + CAPTURE_IMPORT_ERROR if CAPTURE_IMPORT_ERROR else "MR18 non connectée. Sélectionnez une interface."
        self.xruns = 0
        self.capture_drops = 0
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
        if sd is None:
            return []
        apis = sd.query_hostapis()
        out = []
        for d in sd.query_devices():
            if d["max_input_channels"] <= 0:
                continue
            driver = apis[d["hostapi"]]["name"]
            mr18 = "mr18" in d["name"].lower()
            asio = "ASIO" in driver
            usable = not (WINDOWS and mr18 and not asio)
            out.append({"id": int(d["index"]), "name": d["name"], "driver": driver,
                        "inputs": int(d["max_input_channels"]), "defaultRate": float(d["default_samplerate"]),
                        "mr18": bool(mr18), "asio": bool(asio), "usable": bool(usable),
                        "reason": "" if usable else "MR18 multicanal sous Windows : pilote ASIO requis"})
        return out

    def capture(self, data, frames, timing, status):
        # Audio callback: bounded copy only, no file/network I/O or asyncio per sample.
        if status:
            self.xruns += 1
        if len(self.raw) == self.raw.maxlen:
            self.capture_drops += 1
        self.raw.append((time.monotonic(), data.copy()))
        self.last_capture = time.monotonic()

    def open_device(self, device_id, buffer):
        if sd is None:
            raise ValueError("Capture indisponible : " + CAPTURE_IMPORT_ERROR)
        if self.stream:
            self.stream.close()
        self.stream = None
        self.raw.clear()
        self.last_capture = 0
        self.peaks.fill(0)
        self.rms.fill(0)
        devices = self.devices()
        device = next((d for d in devices if d["id"] == device_id), None)
        if not device or device["inputs"] < CHANNELS:
            raise ValueError("Cette interface ne fournit pas 18 entrées simultanées")
        if WINDOWS and device["mr18"] and not device["asio"]:
            raise ValueError("MR18 détectée via " + device["driver"] + " : ASIO requis pour les 18 canaux séparés. Installe/active le pilote USB ASIO Midas.")
        self.wanted = {"name": device["name"], "driver": device["driver"]}
        self.buffer = buffer
        extra = sd.AsioSettings(channel_selectors=list(range(CHANNELS))) if device["asio"] else None
        sd.check_input_settings(device=device_id, channels=CHANNELS, samplerate=RATE, dtype="float32", extra_settings=extra)
        stream = sd.InputStream(device=device_id, channels=CHANNELS, samplerate=RATE, blocksize=buffer,
                                latency="low", dtype="float32", extra_settings=extra, callback=self.capture)
        try:
            stream.start()
        except Exception:
            stream.close()
            raise
        self.device = device
        self.stream = stream
        self.error = ""

    def connected(self):
        return bool(self.stream and self.stream.active and time.monotonic() - self.last_capture < 1)

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
        return {k: p.get(k) for k in ("id", "name", "role", "mix", "mixVersion", "allowed", "locked", "talkAllowed", "talkListen")} | {
            "connected": bool(c and c["pc"].connectionState == "connected"),
            "device": c.get("device", "—") if c else "—", "network": c.get("metrics", {}).get("network", "Inconnu") if c else "—",
            "metrics": c.get("metrics", {}) if c else {}, "droppedFrames": c["track"].dropped if c else 0}

    def status(self):
        connected = self.connected()
        def db(v):
            return round(20 * np.log10(max(float(v), 1e-6)), 1)
        return {"service": "fosa-audio-bridge", "version": VERSION, "protocol": 1,
                "session": self.saved["session"], "experimental": True, "connected": connected,
                "device": self.device, "sampleRate": RATE if connected else None,
                "requestedRate": RATE, "buffer": self.stream.blocksize if connected else None,
                "requestedBuffer": self.buffer, "inputs": CHANNELS if connected else 0,
                "captureLatencyMs": round(self.stream.latency * 1000, 2) if connected else None,
                "audioLatencyMs": None, "latencyMethod": "Non mesurée : test physique nécessaire",
                "opusFrameMs": 20, "cpu": self.cpu, "ramMB": round(self.process.memory_info().rss / 1048576, 1) if self.process else None,
                "xruns": self.xruns, "captureDrops": self.capture_drops,
                "error": self.error, "clients": sum(c["pc"].connectionState == "connected" for c in self.clients.values()),
                "channels": [dict(c, rmsDb=db(self.rms[i]) if connected else None,
                                  peakDb=db(self.peaks[i]) if connected else None, active=connected,
                                  signal=bool(connected and self.rms[i] > 1e-4),
                                  clipping=bool(connected and time.monotonic()-self.clip_at[i] < 2))
                             for i, c in enumerate(self.saved["channels"])]}

    async def health(self, req):
        return json_response({"service": "fosa-audio-bridge", "protocol": 1, "version": VERSION})

    async def join(self, req):
        b = await req.json()
        now = time.monotonic()
        attempts = [t for t in self.auth_failures.get(req.remote, []) if now-t < 60]
        if len(attempts) >= 10:
            raise web.HTTPTooManyRequests(text="Patientez une minute")
        if not hmac.compare_digest(str(b.get("code", "")), self.saved["joinCode"]):
            self.auth_failures[req.remote] = attempts + [now]
            raise web.HTTPUnauthorized(text="Code session incorrect")
        if len(self.saved["profiles"]) >= 64:
            raise web.HTTPServiceUnavailable(text="Limite de 64 profils atteinte")
        pid = str(uuid.uuid4())
        p = {"id": pid, "token": secrets.token_urlsafe(32), "name": str(b.get("name", "Musicien"))[:60],
             "role": str(b.get("role", "Personnalisé"))[:40], "mix": default_mix(),
             "mixVersion": 0, "allowed": [True]*CHANNELS, "locked": False, "talkAllowed": False, "talkListen": True}
        self.saved["profiles"][pid] = p
        self.persist()
        return json_response({"token": p["token"], "profile": self.public_profile(p), "session": self.saved["session"]})

    async def get_state(self, req):
        p = self.profile(req)
        result = self.status()
        result["profile"] = self.public_profile(p) if p else None
        result["admin"] = p is None
        if p is None:
            result["users"] = [self.public_profile(v) for v in self.saved["profiles"].values()]
        return json_response(result)

    async def list_devices(self, req):
        self.profile(req, admin=True)
        return json_response({"devices": self.devices()})

    async def get_meters(self, req):
        self.profile(req)
        connected = self.connected()
        def db(v):
            return round(20 * np.log10(max(float(v), 1e-6)), 1)
        channels = [{
            "active": bool(connected),
            "rmsDb": db(self.rms[i]) if connected else None,
            "peakDb": db(self.peaks[i]) if connected else None,
            "signal": bool(connected and self.rms[i] > 1e-4),
            "clipping": bool(connected and time.monotonic() - self.clip_at[i] < 2),
        } for i in range(CHANNELS)]
        return json_response({"connected": bool(connected), "channels": channels})

    async def configure(self, req):
        self.profile(req, admin=True)
        b = await req.json()
        if any(c["pc"].connectionState == "connected" for c in self.clients.values()):
            raise web.HTTPConflict(text="Arrêtez les écoutes avant de modifier l’interface ou le buffer")
        buffer = int(b.get("buffer", 256))
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
        if not 0 <= i < CHANNELS:
            raise ValueError("Canal invalide")
        for key in ("name", "role", "icon"):
            if key in b:
                self.saved["channels"][i][key] = str(b[key])[:60]
        self.persist()
        return json_response({"ok": True})

    async def mix(self, req):
        caller = self.profile(req)
        b = await req.json()
        p = caller or self.saved["profiles"].get(b.get("id"))
        if not p:
            raise web.HTTPNotFound()
        if caller and p["locked"]:
            raise web.HTTPForbidden(text="Mix verrouillé par le régisseur")
        p["mix"] = clean_mix(b["mix"])
        self.solo_until[p["id"]] = time.monotonic()+1.5
        p["mixVersion"] = p.get("mixVersion", 0)+1
        self.persist()
        return json_response({"mix": p["mix"], "mixVersion": p["mixVersion"]})

    async def matrix(self, req):
        self.profile(req, admin=True)
        b = await req.json()
        p = self.saved["profiles"].get(b.get("id"))
        if not p:
            raise web.HTTPNotFound()
        for key in ("locked", "talkAllowed", "talkListen"):
            if key in b:
                p[key] = b[key] is True
        if "allowed" in b:
            if not isinstance(b["allowed"], list) or len(b["allowed"]) != CHANNELS:
                raise ValueError("18 assignations requises")
            p["allowed"] = [v is True for v in b["allowed"]]
        self.persist()
        return json_response(self.public_profile(p))

    async def close_client(self, pid):
        c = self.clients.pop(pid, None)
        if c:
            c["track"].stop()
            for task in c["tasks"]:
                task.cancel()
            await c["pc"].close()

    async def offer(self, req):
        p = self.profile(req)
        if p is None:
            raise web.HTTPForbidden(text="Rejoignez avec un profil musicien pour écouter")
        b = await req.json()
        await self.close_client(p["id"])
        pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        track = StereoTrack()
        client = {"pc": pc, "track": track, "tasks": [], "previous": None, "limiter": 1., "metrics": {},
                  "voice": deque(maxlen=3), "voiceAt": 0, "talkUntil": 0,
                  "created": time.monotonic(), "target": "all", "device": str(b.get("device", "Navigateur"))[:100]}
        self.clients[p["id"]] = client
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
            pc.addTrack(track)
            opus = [c for c in RTCRtpSender.getCapabilities("audio").codecs if c.mimeType.lower() == "audio/opus"]
            for t in pc.getTransceivers():
                if t.kind == "audio":
                    t.setCodecPreferences(opus)
            await pc.setLocalDescription(await pc.createAnswer())
            return json_response({"type": "answer", "sdp": pc.localDescription.sdp})
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
            c["talkUntil"] = time.monotonic()+1.5 if p["talkAllowed"] and b.get("talk") is True else 0
            c["target"] = str(b.get("target", "all"))[:80]
            m = b.get("metrics")
            if isinstance(m, dict):
                c["metrics"] = {k: m.get(k) for k in ("rtt", "jitter", "loss", "network", "audioLatency")}
        return json_response({"ok": True, "talkAllowed": p["talkAllowed"]})

    async def disconnect(self, req):
        p = self.profile(req)
        if p:
            await self.close_client(p["id"])
        return json_response({"ok": True})

    async def qr(self, req):
        self.profile(req, admin=True)
        role = req.query.get("role", "Personnalisé")[:40]
        from urllib.parse import urlencode
        url = self.args.public_url.rstrip("/") + "/network.html?" + urlencode({"session": self.saved["session"], "role": role})
        out = io.BytesIO()
        qrcode.make(url, image_factory=SvgPathImage).save(out)
        return web.Response(body=out.getvalue(), content_type="image/svg+xml")

    async def pump(self):
        pending = np.empty((0, CHANNELS), dtype=np.float32)
        next_silence = time.monotonic()
        while True:
            blocks = []
            now = time.monotonic()
            while self.raw:
                ts, block = self.raw.popleft()
                if now-ts > .08:  # never accumulate stale audio on a stalled server
                    self.capture_drops += 1
                    pending = np.empty((0, CHANNELS), dtype=np.float32)
                    continue
                pending = np.concatenate((pending, block))
                while len(pending) >= FRAME:
                    blocks.append(pending[:FRAME])
                    pending = pending[FRAME:]
            if not self.connected() and now >= next_silence:
                # Transport keepalive silence, explicitly reported DISCONNECTED in telemetry.
                blocks = [np.zeros((FRAME, CHANNELS), dtype=np.float32)]
                pending = np.empty((0, CHANNELS), dtype=np.float32)
                next_silence = now+.02
            for block in blocks:
                self.peaks = np.maximum(np.max(np.abs(block), axis=0), self.peaks*.98)
                self.rms = np.sqrt(np.mean(block*block, axis=0))
                self.clip_at[np.max(np.abs(block), axis=0) >= .999] = now
                voices = {}
                for pid, c in list(self.clients.items()):
                    if c["voice"]:
                        voice = c["voice"].popleft()
                        if self.saved["profiles"][pid]["talkAllowed"] and c["talkUntil"] > now and now-c["voiceAt"] < .15:
                            voices[pid] = voice
                for pid, c in list(self.clients.items()):
                    p = self.saved["profiles"][pid]
                    if self.solo_until.get(pid, 0) < now:
                        for channel in p["mix"]["channels"]:
                            channel["solo"] = False
                    heard = [v for other, v in voices.items() if other != pid and p["talkListen"] and
                             self.clients[other]["target"] in ("all", pid, "role:"+p["role"])]
                    voice = sum(heard) if heard else None
                    mixed, c["previous"], peak = render_mix(block, p["mix"], p["allowed"], voice, c["previous"])
                    wanted = min(1., .95/max(peak, .001))
                    c["limiter"] = min(wanted, c["limiter"]+.002)
                    c["track"].feed(np.clip(mixed*c["limiter"], -.95, .95))
            await asyncio.sleep(.003)

    async def monitor(self):
        while True:
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
                        candidates = [d for d in devices if d["inputs"] >= CHANNELS and d.get("usable", True) and
                                      ((self.wanted and d["name"] == self.wanted["name"] and d["driver"] == self.wanted["driver"]) or
                                       (not self.wanted and d["mr18"]))]
                        candidates.sort(key=lambda d: not d["asio"])
                        if candidates:
                            await asyncio.to_thread(self.open_device, candidates[0]["id"], self.buffer)
                        else:
                            self.error = "MR18 non connectée" if not self.wanted else "Interface déconnectée — reconnexion en cours"
                except Exception as e:
                    self.error = str(e)
            for pid, c in list(self.clients.items()):
                if c["pc"].connectionState in ("closed", "failed") or (c["pc"].connectionState != "connected" and now-c["created"] > 30):
                    await self.close_client(pid)
            await asyncio.sleep(1)

    async def start(self, app):
        self.tasks = [asyncio.create_task(self.pump()), asyncio.create_task(self.monitor())]
    async def stop(self, app):
        for t in self.tasks:
            t.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        for pid in list(self.clients):
            await self.close_client(pid)
        if self.stream:
            self.stream.close()

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
                        web.post('/api/mix', self.mix), web.post('/api/matrix', self.matrix),
                        web.post('/api/offer', self.offer), web.post('/api/control', self.control),
                        web.post('/api/disconnect', self.disconnect), web.get('/api/qr', self.qr)])
        # Never expose state.json, admin keys, source tree or arbitrary filesystem paths.
        files = {'/': 'network.html', '/network.html': 'network.html', '/network.js': 'network.js',
                 '/network.css': 'network.css', '/fosa-icon.svg': 'fosa-icon.svg'}
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
    parser.add_argument('--data-dir', default=str(Path.home()/'.fosa-audio'))
    args = parser.parse_args()
    if args.host not in ('127.0.0.1', 'localhost', '::1') and not (args.cert and args.key):
        parser.error('Le mode LAN exige --cert et --key (HTTPS). Voir README.')
    bridge = Bridge(args)
    print(f'FOSA Audio Bridge {VERSION}\nConsole : {args.public_url}/network.html')
    print(f'Code musiciens : {bridge.saved["joinCode"]}\nClé régisseur (privée) : {bridge.saved["admin"]}')
    context = None
    if args.cert and args.key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.cert, args.key)
    web.run_app(bridge.app(), host=args.host, port=args.port, ssl_context=context, access_log=None)
