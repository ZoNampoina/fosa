@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
 echo Lance d'abord start-windows.cmd pour installer le bridge, puis ferme-le.
 pause
 exit /b 1
)
set /p FOSA_LAN_IP=Adresse IPv4 du PC sur le reseau local (ex: 192.168.1.10) :
if not defined FOSA_LAN_IP exit /b 1
.venv\Scripts\python.exe make-certificate.py --ip "%FOSA_LAN_IP%"
if errorlevel 1 (
 pause
 exit /b 1
)
echo Installe certs\fosa-local-ca.crt sur les appareils de confiance selon le README.
echo Le pare-feu Windows doit autoriser Python sur le reseau prive uniquement.
set SD_ENABLE_ASIO=1
.venv\Scripts\python.exe bridge.py --host 0.0.0.0 --cert certs/server.crt --key certs/server.key --public-url "https://%FOSA_LAN_IP%:8765"
pause
