"""Crypto/RPC tests. Generated signals and keys here are test fixtures only."""
import asyncio
import json
from pathlib import Path
import secrets
import sys
import time
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from secure_relay import RelayIdentity, SecureRelay, b64, unb64

class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.identity = RelayIdentity()
        self.epoch = secrets.token_hex(16)
        self.calls = 0
        async def dispatch(message):
            self.calls += 1
            await asyncio.sleep(0)
            return {"status": 200, "data": {"token": "test-profile-token"}}
        self.relay = SecureRelay(self.identity, self.epoch, {}, dispatch)
        client = ec.generate_private_key(ec.SECP256R1())
        self.peer = b64(client.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint))
        shared = client.exchange(ec.ECDH(), self.identity.private.public_key())
        material = HKDF(algorithm=hashes.SHA256(), length=64, salt=None,
                        info=("fosa-relay-v1:"+self.identity.id).encode()).derive(shared)
        self.request_key, self.response_key = AESGCM(material[:32]), AESGCM(material[32:])
        self.aad = (self.identity.id+":"+self.epoch+":"+self.peer).encode()

    async def asyncTearDown(self):
        await self.relay.stop()

    def request(self, **changes):
        body = {"id": "test-join-once", "path": "join", "body": {"code": "TEST-CODE", "name": "Private test name"},
                "sentAt": time.time()*1000, **changes}
        iv = secrets.token_bytes(12)
        return {"v": 1, "kind": "request", "epoch": self.epoch, "peer": self.peer, "iv": b64(iv),
                "data": b64(self.request_key.encrypt(iv, json.dumps(body).encode(), self.aad))}

    async def test_encrypted_roundtrip_and_duplicate_join_is_idempotent(self):
        replies = []
        async def send(value): replies.append(value)
        envelope = self.request()
        self.assertNotIn("TEST-CODE", json.dumps(envelope))
        await asyncio.gather(self.relay.respond(envelope, send), self.relay.respond(envelope, send))
        self.assertEqual(self.calls, 1)
        self.assertEqual(len(replies), 2)
        result = json.loads(self.response_key.decrypt(unb64(replies[0]["iv"]), unb64(replies[0]["data"]), self.aad))
        self.assertEqual(result["data"]["token"], "test-profile-token")
        self.assertNotIn("test-profile-token", json.dumps(replies))
        self.assertEqual(RelayIdentity(self.identity.export_private()).id, self.identity.id)

    async def test_tamper_and_previous_start_are_rejected(self):
        replies = []
        async def send(value): replies.append(value)
        bad = self.request(); data = bytearray(unb64(bad["data"])); data[0] ^= 1; bad["data"] = b64(data)
        await self.relay.respond(bad, send)
        old = self.request(); old["epoch"] = "0"*32
        await self.relay.respond(old, send)
        self.assertEqual(self.calls, 0)
        self.assertEqual(replies, [])

    async def test_expired_request_is_not_dispatched(self):
        replies = []
        async def send(value): replies.append(value)
        await self.relay.respond(self.request(sentAt=(time.time()-300)*1000), send)
        self.assertEqual(self.calls, 0)
        result = json.loads(self.response_key.decrypt(unb64(replies[0]["iv"]), unb64(replies[0]["data"]), self.aad))
        self.assertEqual(result["data"]["code"], "CLOCK_SKEW")
