"""Optional mDNS advertisement. No internet, audio or join secret in discovery."""
import socket
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

class StageDiscovery:
    def __init__(self):
        self.server = None
        self.info = None
        self.state = 'unavailable'
    async def start(self, addresses, port):
        if not addresses:
            return
        self.server = AsyncZeroconf(interfaces=addresses)
        self.info = ServiceInfo('_fosa._tcp.local.', 'FOSA STAGE._fosa._tcp.local.',
            addresses=[socket.inet_aton(a) for a in addresses], port=port,
            properties={'version': '0.10', 'audio': 'pcm-udp', 'rate': '48000'},
            server='fosa-stage.local.')
        await self.server.async_register_service(self.info, allow_name_change=True)
        self.state = 'ready'
    async def close(self):
        if self.server:
            if self.info:
                await self.server.async_unregister_service(self.info)
            await self.server.async_close()
