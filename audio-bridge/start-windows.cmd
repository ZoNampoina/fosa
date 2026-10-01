@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title FOSA - Console locale
call setup-windows.cmd
if errorlevel 1 goto error
set SD_ENABLE_ASIO=1
".venv\Scripts\python.exe" -u bridge.py --open-browser
pause
exit /b
:error
echo FOSA n'a pas pu demarrer. Le message ci-dessus indique la cause.
pause
exit /b 1
