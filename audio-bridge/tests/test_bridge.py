"""Integration tests use a labelled DSP fixture, never a product demo mode."""
import asyncio
import argparse
import contextlib
import json
import tempfile
import time
import unittest
from unittest.mock import patch
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from aiohttp.test_utils import TestClient, TestServer
from aiortc import RTCPeerConnection, RTCConfiguration, RTCSessionDescription
from bridge import Bridge

class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.bridge=Bridge(argparse.Namespace(data_dir=self.tmp.name,public_url='http://127.0.0.1:8765',allow_origin=[]))
        self.client=TestClient(TestServer(self.bridge.app()))
        await self.client.start_server()
        self.admin={'Authorization':'Bearer '+self.bridge.saved['admin']}
        response=await self.client.post('/api/join',json={'code':self.bridge.saved['joinCode'],'name':'Basse','role':'Bassiste'})
        self.joined=await response.json()
        self.auth={'Authorization':'Bearer '+self.joined['token']}
        self.pid=self.joined['profile']['id']
    async def asyncTearDown(self):
        await self.client.close()
        self.tmp.cleanup()

    async def test_real_disconnected_status_and_authorization(self):
        response=await self.client.get('/api/state')
        self.assertEqual(response.status,401)
        response=await self.client.get('/api/state',headers=self.auth)
        s=await response.json()
        self.assertFalse(s['connected'])
        self.assertIsNone(s['audioLatencyMs'])
        self.assertTrue(all(c['rmsDb'] is None for c in s['channels']))
        self.assertNotIn('admin',json.dumps(s['profile']))
        response=await self.client.get('/api/devices',headers=self.auth)
        self.assertEqual(response.status,401)
        response=await self.client.get('/audio-bridge/state.json',headers=self.admin)
        self.assertEqual(response.status,404)
        response=await self.client.get('/api/state',headers=self.admin|{'Origin':'https://evil.example'})
        self.assertEqual(response.status,403)

    async def test_mix_lock_persistence_and_transient_solo(self):
        mix=self.joined['profile']['mix']
        mix['channels'][0]['gain']=.85
        mix['channels'][0]['solo']=True
        r=await self.client.post('/api/mix',headers=self.auth,json={'mix':mix})
        self.assertEqual(r.status,200)
        saved=json.loads(Path(self.tmp.name,'state.json').read_text())
        self.assertEqual(saved['profiles'][self.pid]['mix']['channels'][0]['gain'],.85)
        self.assertFalse(saved['profiles'][self.pid]['mix']['channels'][0]['solo'])
        r=await self.client.post('/api/matrix',headers=self.admin,json={'id':self.pid,'locked':True,'talkAllowed':False})
        self.assertEqual(r.status,200)
        r=await self.client.post('/api/mix',headers=self.auth,json={'mix':mix})
        self.assertEqual(r.status,403)

    async def test_webrtc_stereo_transport_with_test_input(self):
        # Limit this transport test to localhost, independent of LAN adapter permissions.
        patcher=patch('aioice.ice.get_host_addresses',return_value=['127.0.0.1'])
        patcher.start()
        self.addCleanup(patcher.stop)
        pc=RTCPeerConnection(RTCConfiguration(iceServers=[]))
        pc.addTransceiver('audio',direction='recvonly')
        got=asyncio.Future()
        @pc.on('track')
        def track(t):
            if not got.done():got.set_result(t)
        task=None
        try:
            await pc.setLocalDescription(await pc.createOffer())
            response=await self.client.post('/api/offer',headers=self.auth,json={'sdp':pc.localDescription.sdp})
            self.assertEqual(response.status,200,await response.text())
            answer=await response.json()
            await pc.setRemoteDescription(RTCSessionDescription(**answer))
            remote=await asyncio.wait_for(got,5)
            # Known generated input exists only in this test harness, never in bridge.py.
            self.bridge.connected=lambda: True
            async def produce():
                phase=0
                while True:
                    data=np.zeros((960,18),np.float32)
                    data[:,0]=np.sin(2*np.pi*440*(np.arange(960)+phase)/48000)*.1
                    phase+=960
                    self.bridge.raw.append((time.monotonic(),data))
                    await asyncio.sleep(.02)
            task=asyncio.create_task(produce())
            peak=0
            for _ in range(20):
                frame=await asyncio.wait_for(remote.recv(),5)
                self.assertEqual(frame.sample_rate,48000)
                self.assertEqual(frame.layout.name,'stereo')
                peak=max(peak,float(np.max(np.abs(frame.to_ndarray()))))
            self.assertGreater(peak,100)
            self.assertEqual(pc.connectionState,'connected')
        finally:
            if task:task.cancel()
            await pc.close()

if __name__=='__main__':unittest.main()
