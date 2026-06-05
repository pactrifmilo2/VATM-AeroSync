@echo off
setlocal
cd /d "%~dp0"

if /I "%~1"=="stop" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-aerosync.ps1" -Stop
  goto :done
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-aerosync.ps1" %*
if errorlevel 1 (
  echo.
  echo Run failed. See messages above.
)

:done
pause
