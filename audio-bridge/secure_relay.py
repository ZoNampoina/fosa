"""Encrypted connection setup through FOSA's existing Realtime service.

No audio, microphone samples or administrator RPCs use this relay. Browser and
bridge switch to their authenticated WebRTC data channel after negotiation.
"""
import asyncio
import base64
import contextlib
import hashlib
import json
import secrets
import time
from urllib.parse import urlencode

from aiohttp import ClientSession, ClientTimeout, WSMsgType
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MAX_ENVELOPE = 96 * 1024
JOIN_PAYLOAD = {"config": {"broadcast": {"ack": False, "self": False},
                            "presence": {"enabled": False}, "private": False}}

def b64(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")

def unb64(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))

class RelayIdentity:
    def __init__(self, private=None):
        self.private = (serialization.load_der_private_key(unb64(private), None) if private
                        else ec.generate_private_key(ec.SECP256R1()))
        self.public = self.private.public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        self.id = hashlib.sha256(self.public).hexdigest()[:32]

    def export_private(self):
        return b64(self.private.private_bytes(serialization.Encoding.DER,
                   serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))

    def keys(self, peer):
        public = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), unb64(peer))
        shared = self.private.exchange(ec.ECDH(), public)
        material = HKDF(algorithm=hashes.SHA256(), length=64, salt=None,
                        info=("fosa-relay-v1:" + self.id).encode()).derive(shared)
        return AESGCM(material[:32]), AESGCM(material[32:])

    def open(self, envelope, epoch):
        if (not isinstance(envelope, dict) or envelope.get("v") != 1 or
            envelope.get("kind") != "request" or envelope.get("epoch") != epoch or
            len(json.dumps(envelope)) > MAX_ENVELOPE):
            raise ValueError("Invalid relay envelope")
        peer = envelope["peer"]
        if not isinstance(peer, str) or len(peer) != 87:
            raise ValueError("Invalid client public key")
        request_key, response_key = self.keys(peer)
        aad = (self.id + ":" + epoch + ":" + peer).encode()
        raw = request_key.decrypt(unb64(envelope["iv"]), unb64(envelope["data"]), aad)
        request = json.loads(raw)
        if not isinstance(request, dict) or not isinstance(request.get("id"), str) or len(request["id"]) > 80:
            raise ValueError("Invalid request")
        return request, peer, response_key, aad

    def seal(self, response, peer, key, aad, epoch):
        iv = secrets.token_bytes(12)
        return {"v": 1, "kind": "response", "peer": peer, "epoch": epoch, "iv": b64(iv),
                "data": b64(key.encrypt(iv, json.dumps(response).encode(), aad))}

class SecureRelay:
    def __init__(self, identity, epoch, config, dispatch):
        self.identity, self.epoch, self.config, self.dispatch = identity, epoch, config, dispatch
        self.status = {"state": "connecting", "message": "Connexion sécurisée en préparation…"}
        self.requests = {}
        self.handlers = set()
        self.topic = "realtime:fosa-bridge:" + identity.id
        self.ref = 1
        self.stopping = False

    def descriptor(self):
        return {"relay": self.identity.id, "key": b64(self.identity.public), "epoch": self.epoch}

    async def respond(self, envelope, send):
        try:
            request, peer, key, aad = self.identity.open(envelope, self.epoch)
            now = time.time()
            if abs(now * 1000 - float(request.get("sentAt", 0))) > 120000:
                result = {"status": 409, "data": {"error": "Synchronisation de l’horloge nécessaire", "code": "CLOCK_SKEW"}}
            else:
                # A retransmitted join/offer must never create a second profile or connection.
                self.requests = {k: v for k, v in self.requests.items() if now-v[0] < 180 or not v[1].done()}
                cache_key = (peer, request["id"])
                if cache_key not in self.requests:
                    if len(self.requests) >= 4096:
                        return
                    self.requests[cache_key] = (now, asyncio.create_task(self.dispatch(request)))
                result = await self.requests[cache_key][1]
            response = dict(result, id=request["id"], serverTime=int(time.time()*1000))
            await send(self.identity.seal(response, peer, key, aad, self.epoch))
        except (ValueError, KeyError, TypeError, InvalidTag):
            return  # Malformed or unauthenticated ciphertext is never dispatched.

    async def run(self):
        retry = 0
        async with ClientSession(timeout=ClientTimeout(total=15)) as session:
            while not self.stopping:
                try:
                    url = self.config["url"].replace("https://", "wss://", 1) + "/realtime/v1/websocket?" + urlencode(
                        {"apikey": self.config["publishableKey"], "vsn": "1.0.0"})
                    async with session.ws_connect(url, max_msg_size=MAX_ENVELOPE, receive_timeout=45) as ws:
                        async def push(event, payload, topic=None):
                            self.ref += 1
                            await ws.send_json({"topic": topic or self.topic, "event": event,
                                                "payload": payload, "ref": str(self.ref), "join_ref": "1"})
                        async def send(envelope):
                            await push("broadcast", {"type": "broadcast", "event": "rpc", "payload": envelope})
                        async def heartbeat():
                            while True:
                                await asyncio.sleep(20)
                                await push("heartbeat", {}, "phoenix")
                        await ws.send_json({"topic": self.topic, "event": "phx_join", "payload": JOIN_PAYLOAD,
                                            "ref": "1", "join_ref": "1"})
                        first = await asyncio.wait_for(ws.receive_json(), 12)
                        if first.get("event") != "phx_reply" or first.get("payload", {}).get("status") != "ok":
                            raise RuntimeError("Le relais a refusé la connexion")
                        self.status = {"state": "ready", "message": "QR HTTPS prêt · micro disponible avec autorisation."}
                        retry = 0
                        ping = asyncio.create_task(heartbeat())
                        try:
                            async for msg in ws:
                                if msg.type != WSMsgType.TEXT:
                                    if msg.type in (WSMsgType.CLOSED, WSMsgType.ERROR):
                                        break
                                    continue
                                data = json.loads(msg.data)
                                if data.get("event") in ("phx_close", "phx_error"):
                                    raise RuntimeError("Connexion au relais interrompue")
                                if data.get("event") == "broadcast" and data.get("payload", {}).get("event") == "rpc":
                                    if len(self.handlers) < 16:
                                        task = asyncio.create_task(self.respond(data["payload"].get("payload"), send))
                                        self.handlers.add(task)
                                        task.add_done_callback(self.handlers.discard)
                        finally:
                            ping.cancel()
                            await asyncio.gather(ping, return_exceptions=True)
                    if not self.stopping:
                        raise RuntimeError("Connexion au relais fermée")
                except asyncio.CancelledError:
                    break
                except Exception as error:
                    self.status = {"state": "offline", "message": "Relais HTTPS indisponible. Vérifie Internet sur le PC. " + str(error)[:160]}
                    retry += 1
                    await asyncio.sleep(min(15, 2**min(retry, 4)))

    async def stop(self):
        self.stopping = True
        tasks = list(self.handlers) + [v[1] for v in self.requests.values() if not v[1].done()]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
