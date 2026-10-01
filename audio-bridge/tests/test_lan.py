import sys
import os
import json
import subprocess
import tempfile
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lan import select_adapters, lan_address, powershell, firewall_program

class LanTests(unittest.TestCase):
    @unittest.skipUnless(os.name == 'nt', 'Windows-only read-only firewall helper test')
    def test_windows_firewall_helper_runs_without_changing_rules(self):
        helper=Path(__file__).resolve().parents[1]/'configure-firewall.ps1'
        quote=lambda s:"'"+str(s).replace("'","''")+"'"
        result=powershell(f"& {quote(helper)} -Port 64979 -PythonExe {quote(firewall_program())} -Addresses '192.168.50.1' -CheckOnly")
        self.assertIsInstance(json.loads(result)['ok'],bool)

    @unittest.skipUnless(os.name == 'nt', 'Windows venv process image regression')
    def test_windows_venv_firewall_targets_actual_process(self):
        with tempfile.TemporaryDirectory() as folder:
            subprocess.run([sys.executable, '-m', 'venv', '--without-pip', '--system-site-packages', folder], check=True, timeout=45)
            # Test the same redirector used by the Windows launchers, with no firewall mutation.
            code = """
import json, sys, psutil
sys.path.insert(0, sys.argv[1])
import lan
lan.powershell = lambda command, **kwargs: '{"ok":true}'
result = lan.configure_firewall(8765, ['192.168.50.1'])
print(json.dumps({'launcher':sys.executable, 'image':psutil.Process().exe(), 'rule':result['program']}))
"""
            output = subprocess.check_output([str(Path(folder)/'Scripts'/'python.exe'), '-c', code,
                                              str(Path(__file__).resolve().parents[1])], text=True, timeout=15)
            result = json.loads(output)
            self.assertNotEqual(os.path.normcase(result['launcher']), os.path.normcase(result['image']))
            self.assertEqual(os.path.normcase(result['rule']), os.path.normcase(result['image']))

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
