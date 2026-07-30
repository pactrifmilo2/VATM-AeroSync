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
$processName = [System.IO.Path]::GetFileNameWithoutExtension($exe)

Set-Location $root

if (-not (Test-Path $csproj)) {
    throw "UI project not found at $csproj"
}

function Get-RunningUiProcesses {
    @(Get-Process -Name $processName -ErrorAction SilentlyContinue | Where-Object {
        try {
            [System.IO.Path]::GetFullPath($_.Path) -eq [System.IO.Path]::GetFullPath($exe)
        } catch {
            $false
        }
    })
}

$runningProcesses = @(Get-RunningUiProcesses)

if (-not $Rebuild -and $runningProcesses.Count -gt 0) {
    $processIds = ($runningProcesses | ForEach-Object { $_.Id }) -join ", "
    Write-Host "AeroSync UI is already running (PID: $processIds)." -ForegroundColor Yellow
    exit 0
}

if ($Rebuild -or -not (Test-Path $exe)) {
    if ($runningProcesses.Count -gt 0) {
        Write-Host "Stopping the running AeroSync UI before rebuilding ..." -ForegroundColor Yellow
        foreach ($process in $runningProcesses) {
            $null = $process.CloseMainWindow()
            if (-not $process.WaitForExit(5000)) {
                Stop-Process -Id $process.Id -Force -ErrorAction Stop
                $process.WaitForExit()
            }
        }
    }

    if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
        throw "The dotnet CLI was not found. Install the .NET 8 SDK or add dotnet to PATH."
    }

    Write-Host "Building AeroSync UI ..." -ForegroundColor Cyan
    $result = dotnet build $csproj -p:Platform=x64 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host $result
        throw "UI build failed. Review the dotnet compiler output above."
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
