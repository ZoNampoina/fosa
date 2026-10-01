@echo off
setlocal EnableExtensions
cd /d "%~dp0"

:: Elevate once so Windows Firewall can be configured automatically.
net session >nul 2>&1
if errorlevel 1 (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

if not exist ".venv\Scripts\python.exe" (
 echo Lance d'abord start-windows.cmd une fois pour installer le bridge.
 pause
 exit /b 1
)

set "PY=%CD%\.venv\Scripts\python.exe"

:: Remove stale FOSA rules and create simple local-network rules.
netsh advfirewall firewall delete rule name="FOSA Audio TCP 8765" >nul 2>&1
netsh advfirewall firewall delete rule name="FOSA Audio WebRTC UDP" >nul 2>&1
netsh advfirewall firewall add rule name="FOSA Audio TCP 8765" dir=in action=allow protocol=TCP localport=8765 profile=any >nul
netsh advfirewall firewall add rule name="FOSA Audio WebRTC UDP" dir=in action=allow program="%PY%" protocol=UDP profile=any >nul

:: Prefer a real RFC1918 LAN adapter and ignore common VPN/virtual interfaces.
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "$c=Get-NetIPConfiguration ^| Where-Object { $_.IPv4Address -and $_.NetAdapter.Status -eq 'Up' }; $c=$c ^| Where-Object { $_.InterfaceAlias -notmatch 'Cloudflare|WARP|Tailscale|VPN|vEthernet|Virtual|Loopback|Bluetooth|WSL|Hyper-V' }; $ips=@($c ^| ForEach-Object { $_.IPv4Address.IPAddress } ^| Where-Object { $_ -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' }); if($ips.Count -gt 0){$ips[0]}"`) do set "FOSA_LAN_IP=%%i"

if not defined FOSA_LAN_IP (
 for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "$ips=@(Get-NetIPAddress -AddressFamily IPv4 ^| Where-Object { $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' -and $_.InterfaceAlias -notmatch 'Cloudflare|WARP|Tailscale|VPN|vEthernet|Virtual|Loopback|Bluetooth|WSL|Hyper-V' } ^| Select-Object -ExpandProperty IPAddress); if($ips.Count -gt 0){$ips[0]}"`) do set "FOSA_LAN_IP=%%i"
)

if not defined FOSA_LAN_IP (
 echo.
 echo Impossible de trouver automatiquement l'adresse Wi-Fi/Ethernet du PC.
 echo Connecte le PC et le telephone au meme Wi-Fi puis relance ce fichier.
 pause
 exit /b 1
)

echo.
echo ============================================================
echo               FOSA MOBILE - CONNEXION RAPIDE
echo ============================================================
echo.
echo 1. Connecte le telephone/tablette au MEME Wi-Fi que ce PC.
echo 2. Dans Chrome/Safari, ouvre exactement :
echo.
echo    http://%FOSA_LAN_IP%:8765/network.html
echo.
echo Aucun certificat n'est necessaire pour l'ecoute.
echo Le talkback micro navigateur reste desactive en HTTP.
echo.
echo IP du PC detectee : %FOSA_LAN_IP%
echo Pare-feu FOSA      : configure automatiquement
echo ============================================================
echo.

set SD_ENABLE_ASIO=1
start "" "http://%FOSA_LAN_IP%:8765/network.html"
"%PY%" bridge.py --host 0.0.0.0 --public-url "http://%FOSA_LAN_IP%:8765" --insecure-lan
pause
