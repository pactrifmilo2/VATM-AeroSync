#Requires -Version 5.1
<#
.SYNOPSIS
  Live IMAP connectivity test using credentials from .env

.EXAMPLE
  .\scripts\test-imap.ps1
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
$mvnw = Join-Path $root "aerosync-worker\mvnw.cmd"

function Read-DotEnvValue {
    param([string] $Name)
    if (-not (Test-Path $envFile)) { return $null }
    foreach ($line in Get-Content $envFile) {
        if ($line -match "^\s*#") { continue }
        if ($line -match "^\s*$Name\s*=\s*(.*)\s*$") {
            return $Matches[1].Trim().Trim('"').Trim("'")
        }
    }
    return $null
}

if (-not (Test-Path $envFile)) {
    throw "Missing .env - copy .env.example to .env and set APP_EMAIL_* values first."
}

$emailVars = @(
    "APP_EMAIL_HOST",
    "APP_EMAIL_PORT",
    "APP_EMAIL_PROTOCOL",
    "APP_EMAIL_USERNAME",
    "APP_EMAIL_PASSWORD",
    "APP_EMAIL_FOLDER"
)

Write-Host "Loading IMAP settings from .env..." -ForegroundColor Cyan
foreach ($name in $emailVars) {
    $value = Read-DotEnvValue -Name $name
    if ($null -ne $value) {
        Set-Item -Path "Env:$name" -Value $value
    }
}

if (-not $env:APP_EMAIL_PASSWORD) {
    throw "APP_EMAIL_PASSWORD is empty in .env - set it before running this test."
}

Write-Host "Host:     $env:APP_EMAIL_HOST`:$env:APP_EMAIL_PORT ($env:APP_EMAIL_PROTOCOL)"
Write-Host "User:     $env:APP_EMAIL_USERNAME"
Write-Host "Folder:   $env:APP_EMAIL_FOLDER"
Write-Host ""

Set-Location $root
& $mvnw -f (Join-Path $root "pom.xml") test `
    -pl aerosync-ingest -am `
    "-Dtest=JavaMailEmailClientLiveTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false"
