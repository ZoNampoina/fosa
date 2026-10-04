import sys
import asyncio
import unittest
import struct
import time
from collections import deque
from pathlib import Path
import numpy as np
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from native_audio import NativeProtocol, NativeSender

class NativeTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_udp_authenticated_audio_and_talkback_replay_rejection(self):
        loop=asyncio.get_running_loop();server=NativeProtocol()
        transport,_=await loop.create_datagram_endpoint(lambda:server,local_addr=('127.0.0.1',0))
        received=asyncio.Queue()
        class Phone(asyncio.DatagramProtocol):
            def datagram_received(self,data,addr): received.put_nowait(data)
        phone,_=await loop.create_datagram_endpoint(Phone,remote_addr=transport.get_extra_info('sockname'))
        c={'talkUntil':time.monotonic()+5,'voice':deque(maxlen=4),'voiceAt':0}
        sender=NativeSender(server,c)
        def send(seq,body,tamper=False):
            header=b'FNA1'+sender.id+struct.pack('>I',seq)
            data=header+AESGCM(sender.input_key).encrypt(header[4:],body,header)
            if tamper:data=data[:-1]+bytes([data[-1]^1])
            phone.sendto(data)
        try:
            send(0,b'H',True);await asyncio.sleep(.01)
            self.assertFalse(sender.connected())
            send(0,b'H');await asyncio.sleep(.01)
            self.assertTrue(sender.connected())
            x=np.zeros((240,2),np.float32);x[:,1]=.2
            sender.feed(x,1.25)
            packet=await asyncio.wait_for(received.get(),1)
            self.assertEqual(len(packet),1017)
            plain=AESGCM(sender.output_key).decrypt(packet[4:16],packet[16:],packet[:16])
            self.assertEqual(plain[:5],b'AFLL1')
            pcm=np.frombuffer(plain[25:],'<i2').reshape(240,2)
            self.assertFalse(pcm[:,0].any());self.assertTrue(np.all(pcm[:,1]>6500))
            send(1,b'T'+np.full(240,3276,dtype='<i2').tobytes());await asyncio.sleep(.01)
            self.assertEqual(len(c['voice']),1);self.assertAlmostEqual(c['voice'][0][0,0],.1,places=3)
            send(1,b'T'+bytes(480));await asyncio.sleep(.01)
            self.assertEqual(len(c['voice']),1,'replay rejected')
            c['talkUntil']=0
            send(2,b'T'+bytes(480));await asyncio.sleep(.01)
            self.assertEqual(len(c['voice']),1,'released mic cannot inject audio')
            sender.last_seen-=3
            self.assertFalse(sender.connected())
            sender.close();self.assertFalse(server.streams)
        finally:phone.close();transport.close()
