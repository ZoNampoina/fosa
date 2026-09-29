@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
 echo Installe Python 3.11 ou 3.12 64 bits depuis python.org, puis relance ce fichier.
 pause
 exit /b 1
)
if not exist ".venv\Scripts\python.exe" py -3 -m venv .venv
if errorlevel 1 goto error
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto error
set SD_ENABLE_ASIO=1
start "" "http://127.0.0.1:8765/network.html"
.venv\Scripts\python.exe bridge.py
pause
exit /b
:error
echo Installation incomplete. Consulte le message ci-dessus.
pause
exit /b 1
