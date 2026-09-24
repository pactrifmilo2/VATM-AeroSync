#Requires -Version 5.1
param([string] $InstallRoot = "C:\AeroSync")

$ErrorActionPreference = "Stop"
$uiExe = Join-Path $InstallRoot "ui\AeroSync.UI.exe"

if (-not (Test-Path -LiteralPath $uiExe -PathType Leaf)) {
    throw "Khong tim thay AeroSync UI tai $uiExe."
}

Start-Process -FilePath $uiExe -WorkingDirectory (Split-Path -Parent $uiExe)
