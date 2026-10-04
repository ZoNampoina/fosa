"""Authenticated/encrypted native LAN UDP, 5 ms stereo PCM, no retransmissions.

HTTP join/stream allocation is authenticated with the existing musician token.
Use a trusted dedicated LAN or TLS for that bootstrap. Separate AES-GCM keys
for each direction; 64-bit stream ID + 32-bit non-wrapping counter as nonce.
"""
import asyncio
import secrets
import struct
import time
import numpy as np
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidTag
from low_latency import packet

class NativeSender:
    def __init__(self, protocol, client):
        self.protocol, self.client = protocol, client
        self.id = secrets.token_bytes(8)
        self.output_key, self.input_key = secrets.token_bytes(32), secrets.token_bytes(32)
        self.encrypt, self.decrypt = AESGCM(self.output_key), AESGCM(self.input_key)
        self.address = None
        self.last_seen = 0
        self.in_sequence = -1
        self.sequence = 0
        self.sent = self.drops = 0
        self.queue_ms = None
        protocol.streams[self.id] = self

    def connected(self):
        return self.address is not None and time.monotonic()-self.last_seen < 2

    def feed(self, samples, capture_wait_ms):
        if not self.connected() or self.sequence >= 0xffffffff:
            return
        seq = self.sequence
        self.sequence += 1
        header = b'FNA1' + self.id + struct.pack('>I', seq)
        body = self.encrypt.encrypt(header[4:], b'A'+packet(samples, seq, capture_wait_ms), header)
        try:
            self.protocol.transport.sendto(header+body, self.address)
            self.sent += 1
            self.queue_ms = round(capture_wait_ms, 2)
        except (OSError, RuntimeError):
            self.drops += 1

    def status(self):
        return {'sent': self.sent, 'congestionDrops': self.drops, 'captureQueueMs': self.queue_ms,
                'packetMs': 5, 'bufferedBytes': 0, 'transport': 'UDP/AES-GCM'}

    def close(self):
        self.protocol.streams.pop(self.id, None)

class NativeProtocol(asyncio.DatagramProtocol):
    def __init__(self):
        self.streams = {}
        self.transport = None
        self.invalid = 0

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if len(data) not in (33, 513) or data[:4] != b'FNA1':
            self.invalid += 1
            return
        sender = self.streams.get(data[4:12])
        if not sender:
            return
        seq = struct.unpack('>I', data[12:16])[0]
        if seq <= sender.in_sequence:
            return
        try:
            body = sender.decrypt.decrypt(data[4:16], data[16:], data[:16])
        except (InvalidTag, ValueError):
            self.invalid += 1
            return
        if body not in (b'H',) and not (len(body) == 481 and body[:1] == b'T'):
            return
        sender.in_sequence = seq
        sender.address = addr
        sender.last_seen = time.monotonic()
        if body[:1] == b'T' and sender.client['talkUntil'] > sender.last_seen:
            mono = np.frombuffer(body[1:], '<i2').astype(np.float32)/32768
            sender.client['voice'].append(np.repeat(mono[:, None], 2, axis=1))
            sender.client['voiceAt'] = sender.last_seen
