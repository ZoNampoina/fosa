@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
 echo Lance d'abord start-windows.cmd une fois pour installer le bridge.
 pause
 exit /b 1
)
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "$ip=(Get-NetIPConfiguration ^| Where-Object {$_.IPv4DefaultGateway -ne $null -and $_.IPv4Address -ne $null} ^| Select-Object -First 1 -ExpandProperty IPv4Address).IPAddress; if($ip){$ip}"`) do set FOSA_LAN_IP=%%i
if not defined FOSA_LAN_IP (
 echo Adresse IP introuvable automatiquement.
 set /p FOSA_LAN_IP=Entre l'adresse IPv4 du PC :
)
echo.
echo ============================================
echo FOSA MOBILE RAPIDE
echo Meme Wi-Fi requis.
echo Ouvre sur le mobile :
echo http://%FOSA_LAN_IP%:8765/network.html
echo.
echo Ecoute audio : OUI
echo Talkback micro navigateur : NON dans ce mode HTTP
echo ============================================
echo.
set SD_ENABLE_ASIO=1
start "" "http://%FOSA_LAN_IP%:8765/network.html"
.venv\Scripts\python.exe bridge.py --host 0.0.0.0 --public-url "http://%FOSA_LAN_IP%:8765" --insecure-lan
pause
