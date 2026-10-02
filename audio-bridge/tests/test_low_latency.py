import struct
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import numpy as np
from low_latency import packet, HEADER, PACKET_BYTES, MAX_BUFFERED_BYTES, PcmSender


class PcmTests(unittest.TestCase):
    def test_binary_contract_and_channel_independence(self):
        data=np.zeros((240,2), np.float32)
        data[:,0]=.25
        payload=packet(data,7,2.125)
        self.assertEqual(len(payload),984)
        self.assertEqual(HEADER.unpack(payload[:24]),(b'FLL1',7,1680,240,2,0,48000,2125))
        samples=np.frombuffer(payload[24:],dtype='<i2').reshape(240,2)
        self.assertTrue(np.all(samples[:,0]==8191))
        self.assertFalse(samples[:,1].any())

    def test_wrap_saturation_and_invalid_dsp(self):
        data=np.full((240,2),3,np.float32)
        payload=packet(data,0xffffffff)
        self.assertEqual(HEADER.unpack(payload[:24])[2],(0xffffffff*240)&0xffffffff)
        self.assertTrue(np.all(np.frombuffer(payload[24:],'<i2')==32767))
        for invalid in [np.zeros((960,2)),np.full((240,2),np.nan)]:
            with self.assertRaises(ValueError):packet(invalid,0)

    def test_congestion_discards_audio_and_preserves_sequence_gap(self):
        sent=[]
        sender=PcmSender()
        channel=SimpleNamespace(readyState='open',bufferedAmount=MAX_BUFFERED_BYTES,send=sent.append)
        sender.channel=channel
        data=np.zeros((240,2))
        sender.feed(data,1)
        self.assertFalse(sent)
        self.assertEqual(sender.congestion_drops,1)
        channel.bufferedAmount=MAX_BUFFERED_BYTES-PACKET_BYTES
        sender.feed(data,2)
        self.assertEqual(struct.unpack_from('<I',sent[0],4)[0],1)
        self.assertEqual(sender.status()['captureQueueMs'],2)
        channel.readyState='closed'
        sender.feed(data,3)
        self.assertEqual(len(sent),1)
