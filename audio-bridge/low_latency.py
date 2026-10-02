"""FOSA PCM v1: 5 ms stereo packets over an authenticated WebRTC data channel.

No Opus or retransmission queue. This is a browser transport, not native UDP.
The reported queue duration starts at Python's capture callback, not the ADC.
"""
import struct
import numpy as np

RATE = 48000
SAMPLES = 240
HEADER = struct.Struct('<4sIIHBBII')
PACKET_BYTES = HEADER.size + SAMPLES * 2 * 2
MAX_BUFFERED_BYTES = PACKET_BYTES * 4  # At most 20 ms already queued in SCTP.


def packet(samples, sequence, capture_wait_ms=0):
    if samples.shape != (SAMPLES, 2) or not np.isfinite(samples).all():
        raise ValueError('PCM requires 240 finite stereo samples')
    sequence &= 0xffffffff
    pcm = np.ascontiguousarray((np.clip(samples, -1, 1) * 32767).astype('<i2'))
    wait_us = min(0xffffffff, max(0, int(capture_wait_ms * 1000)))
    return HEADER.pack(b'FLL1', sequence, (sequence*SAMPLES) & 0xffffffff,
                       SAMPLES, 2, 0, RATE, wait_us) + pcm.tobytes()


class PcmSender:
    def __init__(self):
        self.sequence = 0
        self.sent = 0
        self.congestion_drops = 0
        self.queue_ms = None
        self.channel = None

    def feed(self, samples, capture_wait_ms):
        seq = self.sequence
        self.sequence = (seq+1) & 0xffffffff
        ch = self.channel
        if ch is None or ch.readyState != 'open':
            return
        self.queue_ms = round(capture_wait_ms, 2)
        # Dropped sequence numbers remain visible to the receiver.
        if ch.bufferedAmount + PACKET_BYTES > MAX_BUFFERED_BYTES:
            self.congestion_drops += 1
            return
        try:
            ch.send(packet(samples, seq, capture_wait_ms))
            self.sent += 1
        except (ConnectionError, RuntimeError):
            self.congestion_drops += 1

    def status(self):
        return {'sent': self.sent, 'congestionDrops': self.congestion_drops,
                'captureQueueMs': self.queue_ms, 'packetMs': 5,
                'bufferedBytes': self.channel.bufferedAmount if self.channel else 0}
