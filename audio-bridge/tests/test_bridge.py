"""Integration tests use a labelled DSP fixture, never a product demo mode."""
import asyncio
import argparse
import contextlib
import json
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from urllib.parse import urlsplit, parse_qs
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from aiohttp import web
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

    async def test_bodypack_limits_permissions_and_locked_panic(self):
        p=self.bridge.saved['profiles'][self.pid]
        r=await self.client.post('/api/matrix',headers=self.admin,json={'id':self.pid,
            'limits':{'gainMaxDb':6,'masterMax':.7,'moreMeMaxDb':3},'permissions':{'pan':False}})
        self.assertEqual(r.status,200)
        mix=json.loads(json.dumps(p['mix'])) | {'monitorGainDb':12,'master':1}
        mix['channels'][0]['pan']=1
        r=await self.client.post('/api/mix',headers=self.auth,json={'mix':mix})
        applied=(await r.json())['mix']
        self.assertEqual(applied['monitorGainDb'],6)
        self.assertEqual(applied['master'],.7)
        # The API cannot bypass a pan permission, even if a client ignores disabled UI.
        self.assertEqual(applied['channels'][0]['pan'],0)
        await self.client.post('/api/matrix',headers=self.admin,json={'id':self.pid,'locked':True})
        self.assertEqual((await self.client.post('/api/panic',headers=self.auth,json={'active':True})).status,200)
        self.assertTrue(p['mix']['muteAll'])
        self.assertEqual((await self.client.post('/api/panic',headers=self.auth,json={'active':False})).status,403)
        self.assertEqual((await self.client.post('/api/more-me',headers=self.auth,json={'db':6})).status,403)
        saved=json.loads(self.bridge.file.read_text())
        self.assertTrue(saved['profiles'][self.pid]['mix']['muteAll'])
        self.assertFalse(self.bridge.panic)
        await self.client.post('/api/panic',headers=self.admin,json={'active':True})
        self.assertTrue(self.bridge.panic)

    async def test_preset_and_native_api_never_share_private_credentials(self):
        self.assertEqual((await self.client.post('/api/native',headers=self.admin,json={})).status,403)
        self.assertEqual((await self.client.get('/api/presets',headers=self.auth)).status,401)
        self.assertEqual((await self.client.post('/api/presets',headers=self.admin,json={'name':'Rehearsal','action':'save'})).status,200)
        self.assertNotIn(self.joined['token'],json.dumps(self.bridge.saved['presets']))
        self.bridge.saved['profiles'][self.pid]['mix']['monitorGainDb']=9
        await self.client.post('/api/presets',headers=self.admin,json={'name':'Rehearsal','action':'load'})
        self.assertEqual(self.bridge.saved['profiles'][self.pid]['mix']['monitorGainDb'],0)
        r=await self.client.post('/api/native',headers=self.auth,json={})
        self.assertEqual(r.status,200)
        native=await r.json()
        self.assertEqual(native['sampleRate'],48000)
        self.assertNotEqual(native['receiveKey'],native['sendKey'])
        self.assertEqual(len(native['receiveKey']),64)
        state=await (await self.client.get('/api/state',headers=self.auth)).json()
        self.assertNotIn(native['receiveKey'],json.dumps(state))
        self.assertEqual(state['profile']['monitoringEngine'],'native')
        advice=await (await self.client.get('/api/auto-level',headers=self.auth)).json()
        self.assertFalse(advice['available'])

    async def test_more_me_and_revocation_of_active_solo(self):
        p=self.bridge.saved['profiles'][self.pid]
        p['mix']['mainChannel']=2
        r=await self.client.post('/api/more-me',headers=self.auth,json={'db':3})
        m=(await r.json())['mix']
        self.assertAlmostEqual(m['channels'][2]['gain'],.5*10**(.15))
        self.assertEqual(m['channels'][0]['gain'],.5)
        p['mix']['channels'][2]['solo']=True
        await self.client.post('/api/matrix',headers=self.admin,json={'id':self.pid,'permissions':{'solo':False}})
        self.assertFalse(any(c['solo'] for c in p['mix']['channels']))

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

    async def test_secure_rpc_never_exposes_console_or_another_profile(self):
        for path in ['local-console', 'regisseur', 'network', 'matrix', 'configure', '../state.json']:
            result = await self.bridge.musician_rpc({'path':path, 'token':self.joined['token'], 'body':{}})
            self.assertEqual(result['status'],403)
        result = await self.bridge.musician_rpc({'path':'state', 'token':self.bridge.saved['admin']})
        self.assertEqual(result['status'],403)
        result = await self.bridge.musician_rpc({'path':'state', 'token':self.joined['token']})
        self.assertEqual(result['status'],200)
        self.assertNotIn('users',result['data'])
        self.assertEqual(result['data']['profile']['id'],self.pid)
        result = await self.bridge.musician_rpc({'path':'control', 'token':self.joined['token'], 'body':None})
        self.assertEqual(result['status'],400)

    async def test_local_regisseur_identity_is_private_persistent_and_audio_only(self):
        headers = self.admin | {'X-FOSA-Console':'1'}
        response = await self.client.post('/api/regisseur', headers=headers)
        self.assertEqual(response.status,200)
        operator = await response.json()
        pid = operator['profile']['id']
        self.assertEqual(operator['profile']['name'],'Régisseur PC')
        self.assertTrue(operator['profile']['talkAllowed'])
        self.assertEqual(operator['profile']['mix']['master'],0)
        self.assertNotEqual(operator['token'],self.bridge.saved['admin'])
        auth = {'Authorization':'Bearer '+operator['token']}
        state = await (await self.client.get('/api/state', headers=auth)).json()
        self.assertFalse(state['admin'])
        self.assertNotIn('users',state)
        self.assertEqual(state['profile']['id'],pid)
        self.assertEqual((await self.client.post('/api/matrix',headers=auth,
                         json={'id':self.pid,'talkAllowed':True})).status,401)
        again = await (await self.client.post('/api/regisseur',headers=headers)).json()
        self.assertEqual(again['token'],operator['token'])
        self.assertEqual(len(self.bridge.saved['profiles']),2)
        restarted = Bridge(self.bridge.args)
        self.assertEqual(restarted.saved['regisseurProfile'],pid)
        self.assertEqual(restarted.saved['profiles'][pid]['token'],operator['token'])
        await self.client.post('/api/matrix',headers=self.admin,json={'id':pid,'talkAllowed':False})
        again = await (await self.client.post('/api/regisseur',headers=headers)).json()
        self.assertFalse(again['profile']['talkAllowed'],'Opening the console must preserve a revoked permission')

    async def test_regisseur_profile_requires_authenticated_same_origin_loopback(self):
        for headers, status in [({},401), (self.auth | {'X-FOSA-Console':'1'},401),
                                (self.admin,403),
                                (self.admin | {'X-FOSA-Console':'1','Host':'attacker.example'},403),
                                (self.admin | {'X-FOSA-Console':'1','Origin':'https://evil.example'},403)]:
            response = await self.client.post('/api/regisseur',headers=headers)
            self.assertEqual(response.status,status)
        self.assertNotIn('regisseurProfile',self.bridge.saved)
        with self.assertRaises(web.HTTPForbidden):
            self.bridge.require_local_console(SimpleNamespace(remote='192.168.1.44',
                url=SimpleNamespace(host='127.0.0.1'),headers={'X-FOSA-Console':'1'},scheme='http',host='127.0.0.1:8765'))
        result = await self.bridge.musician_rpc({'path':'regisseur','token':self.bridge.saved['admin'],'body':{}})
        self.assertEqual(result['status'],403)
        ordinary = await (await self.client.post('/api/join',json={
            'code':self.bridge.saved['joinCode'],'name':'Un autre régisseur','role':'Régisseur'})).json()
        self.assertFalse(ordinary['profile']['talkAllowed'])

    async def test_release_and_revocation_cannot_be_reopened_by_old_control(self):
        from bridge import StereoTrack
        from collections import deque
        pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        client={'pc':pc, 'track':StereoTrack(), 'tasks':[], 'voice':deque(), 'voiceAt':0,
                'talkUntil':0, 'target':'all', 'created':time.monotonic(), 'controlSeq':-1,
                'previous':None, 'limiter':1., 'dataChannel':object()}
        self.bridge.clients[self.pid]=client
        p=self.bridge.saved['profiles'][self.pid]
        p['talkAllowed']=True
        async def control(sequence,talk):
            return await self.bridge.musician_rpc({'path':'control','body':{'sequence':sequence,'talk':talk}},client,self.pid)
        self.assertTrue((await control(1,True))['data']['talkActive'])
        self.assertFalse((await control(2,False))['data']['talkActive'])
        self.assertFalse((await control(1,True))['data']['talkActive'])
        late=await self.bridge.musician_rpc({'path':'control','token':p['token'],'body':{'talk':True}})
        self.assertEqual(late['status'],409)
        await control(3,True)
        await self.client.post('/api/matrix',headers=self.admin,json={'id':self.pid,'talkAllowed':False})
        self.assertEqual(client['talkUntil'],0)
        self.assertFalse((await control(4,True))['data']['talkActive'])

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

    async def test_qr_invite_authentication_and_local_console(self):
        r=await self.client.post('/api/local-console', headers={'X-FOSA-Console':'1'})
        self.assertEqual(r.status,200)
        self.assertEqual((await r.json())['token'],self.bridge.saved['admin'])
        r=await self.client.post('/api/local-console', headers={'X-FOSA-Console':'1','Host':'attacker.example'})
        self.assertEqual(r.status,403)
        r=await self.client.post('/api/local-console', headers={'X-FOSA-Console':'1','Origin':'https://evil.example'})
        self.assertEqual(r.status,403)
        r=await self.client.post('/api/local-console')
        self.assertEqual(r.status,403)
        r=await self.client.get('/api/network',headers=self.auth)
        self.assertEqual(r.status,401)
        r=await self.client.get('/api/network',headers=self.admin)
        network=await r.json()
        invite=parse_qs(urlsplit(network['joinUrl']).fragment)
        self.assertEqual(invite['code'],[self.bridge.saved['joinCode']])
        self.assertNotIn(self.bridge.saved['admin'],network['joinUrl'])
        r=await self.client.get('/api/qr',headers=self.admin)
        self.assertEqual(r.status,200)
        self.assertIn('<svg',await r.text())
        count=len(self.bridge.saved['profiles'])
        r=await self.client.post('/api/join',json={'code':invite['code'][0],'session':'stale-session'})
        self.assertEqual(r.status,409)
        self.assertEqual(len(self.bridge.saved['profiles']),count)
        self.bridge.network['adapters']=[{'address':'192.168.1.12'}]
        self.assertFalse(self.bridge.remote_device(SimpleNamespace(remote='127.0.0.1')))
        self.assertFalse(self.bridge.remote_device(SimpleNamespace(remote='192.168.1.12')))
        self.assertTrue(self.bridge.remote_device(SimpleNamespace(remote='192.168.1.44')))

    async def test_mr18_non_asio_refused_and_explicit_channel_selectors(self):
        fake=SimpleNamespace(
            query_hostapis=lambda:[{'name':'Windows MME'},{'name':'ASIO'}],
            query_devices=lambda:[{'name':'MR18','index':0,'hostapi':0,'max_input_channels':18,'default_samplerate':48000},
                                  {'name':'MIDAS USB Audio','index':1,'hostapi':1,'max_input_channels':18,'default_samplerate':48000}])
        opened=[]
        stream=SimpleNamespace(active=True,channels=18,samplerate=48000,blocksize=256,latency=.005,
                               start=lambda:None,close=lambda:None)
        fake.AsioSettings=lambda **kw: kw
        fake.check_input_settings=lambda **kw:opened.append(kw)
        fake.InputStream=lambda **kw:(opened.append(kw) or stream)
        with patch('bridge.sd',fake),patch('bridge.WINDOWS',True):
            self.assertFalse(self.bridge.devices()[0]['usable'])
            self.assertTrue(self.bridge.devices()[1]['midasUsb'])
            with self.assertRaisesRegex(ValueError,'ASIO requis'):
                self.bridge.open_device(0,256)
            self.bridge.open_device(1,256)
            self.bridge.last_capture=time.monotonic()
            self.assertTrue(self.bridge.connected())
            self.assertEqual(opened[-1]['extra_settings']['channel_selectors'],list(range(18)))
            self.assertEqual(opened[-1]['samplerate'],48000)
            stream.samplerate=44100
            self.assertFalse(self.bridge.connected())
        self.bridge.stream=None

    async def test_capture_channel_three_stays_independent_in_meters(self):
        self.bridge.connected=lambda:True
        data=np.zeros((960,18),np.float32)
        data[:,2]=.25
        self.bridge.capture(data,960,None,None)
        await asyncio.sleep(.015)
        r=await self.client.get('/api/meters',headers=self.auth)
        m=await r.json()
        self.assertEqual([i+1 for i,c in enumerate(m['channels']) if c['signal']],[3])
        self.assertAlmostEqual(m['channels'][2]['rmsDb'],-12,delta=.2)
        self.assertAlmostEqual(m['channels'][2]['peakDb'],-12,delta=.2)

    async def test_pcm_transport_uses_authorized_personal_mix_without_opus(self):
        patcher=patch('aioice.ice.get_host_addresses',return_value=['127.0.0.1'])
        patcher.start()
        self.addCleanup(patcher.stop)
        pc=RTCPeerConnection(RTCConfiguration(iceServers=[]))
        pc.addTransceiver('audio',direction='sendrecv')
        channel=pc.createDataChannel('fosa-pcm-v1',ordered=False,maxRetransmits=0)
        queue=asyncio.Queue()
        @channel.on('message')
        def received(data):queue.put_nowait(data)
        try:
            await pc.setLocalDescription(await pc.createOffer())
            r=await self.client.post('/api/offer',headers=self.auth,json={
                'sdp':pc.localDescription.sdp,'monitoringEngine':'pcm'})
            answer=await r.json()
            self.assertEqual(r.status,200,answer)
            self.assertEqual(answer.pop('monitoringEngine'),'pcm')
            await pc.setRemoteDescription(RTCSessionDescription(**answer))
            for _ in range(200):
                if channel.readyState=='open':break
                await asyncio.sleep(.01)
            self.assertEqual(channel.readyState,'open')
            self.assertTrue(all(t.direction=='recvonly' for t in self.bridge.clients[self.pid]['pc'].getTransceivers()))
            p=self.bridge.saved['profiles'][self.pid]
            p['mix']['master']=1
            for c in p['mix']['channels']:c['gain']=0
            p['mix']['channels'][2].update(gain=1,pan=1)
            self.bridge.connected=lambda:True
            self.bridge.clients[self.pid]['previous']=None
            data=np.zeros((240,18),np.float32);data[:,2]=.2
            self.bridge.capture(data,240,None,None)
            for _ in range(20):
                payload=await asyncio.wait_for(queue.get(),2)
                samples=np.frombuffer(payload[24:],'<i2').reshape(240,2)
                if samples.any():break  # Already in-flight source-absent keepalive packets.
            # The first block contains the anti-click transition from the old pan/master.
            self.bridge.capture(data,240,None,None)
            payload=await asyncio.wait_for(queue.get(),2)
            samples=np.frombuffer(payload[24:],'<i2').reshape(240,2)
            self.assertLess(np.max(np.abs(samples[:,0])),2)
            self.assertGreater(np.mean(samples[:,1]),6000)
            p['allowed'][2]=False
            self.bridge.clients[self.pid]['previous']=None
            self.bridge.capture(data,240,None,None)
            payload=await asyncio.wait_for(queue.get(),2)
            self.assertFalse(np.frombuffer(payload[24:],'<i2').any())
            self.assertEqual(self.bridge.clients[self.pid]['track'].queue.qsize(),0)
        finally:
            await pc.close()

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
