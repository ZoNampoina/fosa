@echo off
:: Shared bootstrap. No Python command or separate first launch is needed from the user.
if not exist ".venv\Scripts\python.exe" (
  where py >nul 2>nul
  if errorlevel 1 (
    echo Python 3.11 ou 3.12 64 bits est necessaire pour cette version du bridge.
    echo Installe-le depuis python.org. Le futur paquet Windows embarquera ce runtime.
    exit /b 1
  )
  py -3 -m venv .venv
  if errorlevel 1 exit /b 1
)
if not exist ".venv\fosa-deps-v0100.ok" (
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
  if errorlevel 1 exit /b 1
  type nul > ".venv\fosa-deps-v0100.ok"
)
exit /b 0
