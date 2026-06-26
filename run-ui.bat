@echo off
setlocal
cd /d "%~dp0"

  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-ui.ps1" -Rebuild
  goto :done


powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-ui.ps1"
if errorlevel 1 (
  echo.
  echo Launch failed. See messages above.
)

:done
pause
