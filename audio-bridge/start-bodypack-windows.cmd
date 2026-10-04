@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title FOSA SERVER - BODYPACK
call setup-windows.cmd
if errorlevel 1 goto error
set SD_ENABLE_ASIO=1
".venv\Scripts\python.exe" -u bridge.py --musicians --low-latency --native-bodypack --regisseur --open-browser
pause
exit /b
:error
echo Installation FOSA impossible. Consulte le message ci-dessus.
pause
exit /b 1
