@echo off
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if errorlevel 1 (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
    "Start-Process -FilePath '%~f0' -ArgumentList '%*' -WorkingDirectory '%~dp0' -Verb RunAs"
  exit /b
)

echo.
echo Kiem tra moi truong AeroSync va trien khai...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass ^
  -File "%~dp0deploy\AeroSync-Deploy.ps1" %*
set "AEROSYNC_EXIT=%ERRORLEVEL%"

echo.
if "%AEROSYNC_EXIT%"=="0" (
  echo AeroSync da san sang.
) else (
  echo Trien khai khong thanh cong. Vui long gui anh man hinh nay cho ky thuat.
)
echo.
pause
exit /b %AEROSYNC_EXIT%
