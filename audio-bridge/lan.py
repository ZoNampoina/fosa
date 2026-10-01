"""LAN discovery and narrowly scoped Windows firewall setup for the existing bridge."""
import base64
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys

import psutil

EXCLUDED = re.compile(r"warp|cloudflare|tailscale|vpn|wireguard|openvpn|zerotier|hamachi|"
                      r"virtual|vethernet|hyper-v|vmware|virtualbox|bluetooth|loopback|"
                      r"docker|wsl|^tun\d|^tap\d|^utun\d|^lo$|^br-", re.I)
PRIVATE = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]

def lan_address(value):
    try:
        ip = ipaddress.ip_address(value)
        return ip.version == 4 and any(ip in n for n in PRIVATE)
    except ValueError:
        return False

def select_adapters(rows):
    """Do not guess a VPN address when no physical LAN is available."""
    candidates = []
    for row in rows:
        if not row.get("up", True) or row.get("physical") is False:
            continue
        if EXCLUDED.search(row.get("name", "") + " " + row.get("description", "")):
            continue
        if not lan_address(row.get("address", "")):
            continue
        candidates.append(row)
    return sorted(candidates, key=lambda r: (not bool(r.get("gateway")), r.get("metric", 999), r["name"], r["address"]))

def powershell(script, timeout=45):
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded],
                            capture_output=True, timeout=timeout, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "Windows n’a pas terminé le diagnostic réseau.")
    return result.stdout.strip()

def discover():
    if os.name == "nt":
        rows = json.loads(powershell(r"""
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        $ErrorActionPreference='Stop'
        $adapters=@(Get-NetAdapter -Physical | Where-Object { $_.Status -eq 'Up' })
        $ips=@(Get-NetIPAddress -AddressFamily IPv4)
        $routes=@(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue)
        $interfaces=@(Get-NetIPInterface -AddressFamily IPv4)
        $rows=@(foreach($a in $adapters){
          $index=$a.InterfaceIndex
          foreach($ip in @($ips | Where-Object { $_.InterfaceIndex -eq $index })){
            [pscustomobject]@{ name=$a.Name; description=$a.InterfaceDescription;
              address=$ip.IPAddress; prefix=$ip.PrefixLength;
              gateway=($routes | Where-Object { $_.InterfaceIndex -eq $index } | Select-Object -First 1 -ExpandProperty NextHop);
              metric=[int]($interfaces | Where-Object { $_.InterfaceIndex -eq $index } | Select-Object -First 1 -ExpandProperty InterfaceMetric);
              physical=[bool]$a.HardwareInterface; up=($a.Status -eq 'Up');
              type=if($a.NdisPhysicalMedium -eq 9 -or $a.NdisPhysicalMedium -eq 1){'Wi-Fi'}else{'Ethernet'} }
          }
        }); ConvertTo-Json -InputObject $rows -Compress
        """))
    else:
        rows = []
        stats = psutil.net_if_stats()
        for name, addresses in psutil.net_if_addrs().items():
            for a in addresses:
                if a.family == socket.AF_INET:
                    rows.append({"name": name, "address": a.address,
                                 "prefix": ipaddress.ip_network(f"{a.address}/{a.netmask}", strict=False).prefixlen,
                                 "up": bool(stats.get(name) and stats[name].isup), "type": "Réseau local"})
    return select_adapters(rows)

def configure_firewall(port, addresses):
    if os.name != "nt":
        return {"state": "unverified", "message": "Pare-feu de cet OS non vérifié automatiquement."}
    helper = str(Path(__file__).with_name("configure-firewall.ps1"))
    def quoted(s):
        return "'" + str(s).replace("'", "''") + "'"
    invoke = f"& {quoted(helper)} -Port {port} -PythonExe {quoted(sys.executable)} -Addresses {quoted(','.join(addresses))}"
    try:
        checked = json.loads(powershell(invoke + " -CheckOnly"))
        if checked.get("ok"):
            return {"state": "configured", "message": "Règles FOSA vérifiées · sous-réseau local uniquement."}
        # Only this firewall helper is elevated; capture and the browser stay unprivileged.
        command = invoke
        encoded = base64.b64encode(command.encode("utf-16-le")).decode("ascii")
        powershell(f"$ErrorActionPreference='Stop'; Start-Process powershell.exe -Verb RunAs -Wait "
                   f"-ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand','{encoded}')", timeout=55)
        checked = json.loads(powershell(invoke + " -CheckOnly"))
        if not checked.get("ok"):
            raise RuntimeError("Les règles FOSA n’ont pas pu être vérifiées.")
        return {"state": "configured", "message": "Règles FOSA vérifiées · sous-réseau local uniquement."}
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as e:
        return {"state": "blocked", "message": "Autorisation Windows refusée ou pare-feu géré par une politique : " + str(e)[:300]}
