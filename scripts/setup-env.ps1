$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$example = Join-Path $root ".env.example"
$envFile = Join-Path $root ".env"

if (Test-Path $envFile) {
    Write-Host ".env already exists at $envFile"
    exit 0
}

Copy-Item $example $envFile
Write-Host "Created $envFile from .env.example"
Write-Host "Edit .env with your credentials (Gmail app password, etc.) before starting the apps."
