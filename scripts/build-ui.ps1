#Requires -Version 5.1
<#
.SYNOPSIS
  Build and launch the AeroSync WinUI desktop app.
  Skips the build if the exe already exists (use -Rebuild to force).
#>
param(
    [switch] $Rebuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$csproj = Join-Path $root "aerosync-ui\AeroSync.UI.csproj"
$exe = Join-Path $root "aerosync-ui\bin\x64\Debug\net8.0-windows10.0.19041.0\AeroSync.UI.exe"

Set-Location $root

if (-not (Test-Path $csproj)) {
    throw "UI project not found at $csproj"
}

if ($Rebuild -or -not (Test-Path $exe)) {
    Write-Host "Building AeroSync UI ..." -ForegroundColor Cyan
    $result = dotnet build $csproj -p:Platform=x64 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host $result
        throw "UI build failed. Make sure .NET 8 SDK is installed."
    }
    Write-Host "Build complete." -ForegroundColor Green
} else {
    Write-Host "Exe already built. Use -Rebuild to force." -ForegroundColor Yellow
}

if (-not (Test-Path $exe)) {
    throw "Exe not found after build at $exe"
}

Write-Host "Launching ..."
Start-Process -FilePath $exe -WorkingDirectory (Split-Path -Parent $exe)
