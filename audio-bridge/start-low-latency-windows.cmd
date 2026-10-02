@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title FOSA Low-Latency - Regisseur PC
call setup-windows.cmd
if errorlevel 1 goto error
set SD_ENABLE_ASIO=1
".venv\Scripts\python.exe" -u bridge.py --musicians --secure-mobile --low-latency --regisseur --open-browser
pause
exit /b
:error
echo FOSA n'a pas pu demarrer. Le message ci-dessus indique la cause.
pause
exit /b 1
