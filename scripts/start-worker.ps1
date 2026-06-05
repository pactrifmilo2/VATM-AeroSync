$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "_run-app.ps1") -JarName "aerosync-worker"
