"""Isolated browser-test fixture. Never imported or exposed by the production bridge."""
import argparse
import asyncio
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import numpy as np
from aiohttp import web
from bridge import Bridge
from lan import discover

async def main():
    adapters = discover()
    if not adapters:
        raise RuntimeError('A non-loopback LAN address is required to test an insecure HTTP origin')
    port = 8877
    with tempfile.TemporaryDirectory(prefix='fosa-browser-fixture-') as folder:
        args = argparse.Namespace(data_dir=folder, public_url=f'http://{adapters[0]["address"]}:{port}',
                                  port=port, allow_origin=[], network={'adapters':adapters,
                                  'firewall':{'state':'unverified','message':'TEST FIXTURE'},'selfCheck':'passed'})
        bridge = Bridge(args)
        # Known digital input is deliberately restricted to this test process.
        bridge.device={'name':'TEST FIXTURE · no MR18 hardware','driver':'TEST FIXTURE','asio':False,'inputs':18}
        bridge.stream=SimpleNamespace(active=True,channels=18,samplerate=48000,blocksize=256,latency=0,
                                      close=lambda:None)
        async def produce():
            phase=0
            while True:
                data=np.zeros((960,18),np.float32)
                t=(np.arange(960)+phase)/48000
                data[:,0]=np.sin(2*np.pi*440*t)*.1
                data[:,2]=np.sin(2*np.pi*880*t)*.1
                bridge.capture(data,960,None,None)
                phase+=960
                await asyncio.sleep(.02)
        source=asyncio.create_task(produce())
        app=bridge.app()
        # This fixture must never enumerate/open hardware from a CI runner.
        async def test_start(app):
            bridge.tasks=[asyncio.create_task(bridge.pump())]
        app.on_startup.clear()
        app.on_startup.append(test_start)
        runner=web.AppRunner(app,access_log=None)
        await runner.setup()
        await web.TCPSite(runner,'0.0.0.0',port).start()
        print(json.dumps({'url':bridge.join_url(), 'console':f'http://127.0.0.1:{port}/network.html?console=1'}),flush=True)
        try:
            await asyncio.Event().wait()
        finally:
            source.cancel()
            await runner.cleanup()

if __name__=='__main__':
    asyncio.run(main())
