"""Isolated browser-test fixture. Never imported or exposed by the production bridge."""
import argparse
import asyncio
import json
import datetime
import ipaddress
import ssl
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
    secure = '--secure' in sys.argv
    adapters = discover()
    if not adapters:
        raise RuntimeError('A non-loopback LAN address is required to test an insecure HTTP origin')
    port = 8877
    with tempfile.TemporaryDirectory(prefix='fosa-browser-fixture-') as folder:
        args = argparse.Namespace(data_dir=folder, public_url=f'http://{adapters[0]["address"]}:{port}',
                                  port=port, allow_origin=[], secure_mobile=secure, network={'adapters':adapters,
                                  'firewall':{'state':'unverified','message':'TEST FIXTURE'},'selfCheck':'passed'})
        bridge = Bridge(args)
        tls_context = None
        if '--local-https' in sys.argv:
            # An isolated test certificate, never a product/mobile certificate installer.
            from cryptography import x509
            from cryptography.hazmat.primitives import hashes, serialization
            from cryptography.hazmat.primitives.asymmetric import ec
            key=ec.generate_private_key(ec.SECP256R1())
            name=x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'FOSA test fixture')])
            now=datetime.datetime.now(datetime.timezone.utc)
            cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                  .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1))
                  .not_valid_after(now+datetime.timedelta(hours=1))
                  .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),critical=False)
                  .sign(key,hashes.SHA256()))
            cert_file=Path(folder)/'test-cert.pem';key_file=Path(folder)/'test-key.pem'
            cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
            key_file.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
            tls_context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls_context.load_cert_chain(cert_file,key_file)
            bridge.secure.config['clientUrl']='https://127.0.0.1:8878/network.html'
        # Known digital input is deliberately restricted to this test process.
        bridge.device={'name':'TEST FIXTURE · no MR18 hardware','driver':'TEST FIXTURE','asio':False,'inputs':18}
        bridge.stream=SimpleNamespace(active=True,channels=18,samplerate=48000,blocksize=256,latency=0,
                                      close=lambda:None)
        async def produce():
            phase=0
            start=asyncio.get_running_loop().time()
            while True:
                data=np.zeros((240,18),np.float32)
                t=(np.arange(240)+phase)/48000
                data[:,0]=np.sin(2*np.pi*440*t)*.1
                data[:,2]=np.sin(2*np.pi*880*t)*.1
                bridge.capture(data,240,None,None)
                phase+=240
                # Absolute pacing avoids accumulating Python sleep overhead as clock drift.
                target=start+phase/48000
                await asyncio.sleep(max(0,target-asyncio.get_running_loop().time()))
                if asyncio.get_running_loop().time()-target>.03:
                    start=asyncio.get_running_loop().time()-phase/48000
        source=asyncio.create_task(produce())
        app=bridge.app()
        # This fixture must never enumerate/open hardware from a CI runner.
        async def test_start(app):
            bridge.tasks=[asyncio.create_task(bridge.pump())]
            if bridge.secure:
                bridge.tasks.append(asyncio.create_task(bridge.secure.run()))
        app.on_startup.clear()
        app.on_startup.append(test_start)
        runner=web.AppRunner(app,access_log=None)
        await runner.setup()
        await web.TCPSite(runner,'0.0.0.0',port).start()
        if tls_context:
            await web.TCPSite(runner,'127.0.0.1',8878,ssl_context=tls_context).start()
        if bridge.secure:
            for _ in range(120):
                if bridge.secure.status['state']=='ready': break
                await asyncio.sleep(.25)
            else:
                raise RuntimeError(bridge.secure.status['message'])
        print(json.dumps({'url':bridge.join_url(), 'console':f'http://127.0.0.1:{port}/network.html?console=1'}),flush=True)
        try:
            await asyncio.Event().wait()
        finally:
            source.cancel()
            await runner.cleanup()

if __name__=='__main__':
    asyncio.run(main())
