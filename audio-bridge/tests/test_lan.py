import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lan import select_adapters, lan_address

class LanTests(unittest.TestCase):
    def test_physical_lan_wins_over_vpn_and_virtual_interfaces(self):
        rows = [
            dict(name='Cloudflare WARP', address='10.1.0.2', metric=1, physical=True),
            dict(name='Tailscale', address='100.64.0.2', metric=1),
            dict(name='USB VPN', address='192.168.2.1', metric=1, physical=True),
            dict(name='vEthernet', address='172.20.0.1', physical=False),
            dict(name='Ethernet', address='192.168.1.12', gateway='192.168.1.1', metric=10, physical=True),
            dict(name='Wi-Fi', address='192.168.3.12', gateway='192.168.3.1', metric=20, physical=True),
            dict(name='Ethernet 2', address='10.0.1.2', up=False, physical=True),
            dict(name='Bluetooth', address='192.168.4.1', physical=True),
            dict(name='Other', address='10.1.1.1', physical=False),
        ]
        found = select_adapters(rows)
        self.assertEqual([r['name'] for r in found], ['Ethernet', 'Wi-Fi'])

    def test_no_fallback_to_vpn_or_public_address(self):
        self.assertFalse(select_adapters([dict(name='VPN', address='10.1.1.1')]))
        for value in ['127.0.0.1', '169.254.1.2', '8.8.8.8', '100.64.0.2', '::1', 'invalid']:
            self.assertFalse(lan_address(value), value)
        for value in ['10.0.0.2', '192.168.1.2', '172.16.0.2', '172.31.255.2']:
            self.assertTrue(lan_address(value), value)
