#Requires -Version 5.1
<#
.SYNOPSIS
  One-shot local startup for VATM AeroSync (Docker + build + worker + ingest + api).

.EXAMPLE
  .\scripts\run-aerosync.ps1

.EXAMPLE
  .\scripts\run-aerosync.ps1 -SkipBuild
#>
param(
    [switch] $SkipBuild,
    [switch] $SkipDocker,
    [switch] $Stop
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
$example = Join-Path $root ".env.example"
$mvnw = Join-Path $root "aerosync-worker\mvnw.cmd"
$composeFile = Join-Path $root "docker-compose.yml"

function Write-Step {
    param([string] $Message)
    Write-Host ""
    Write-Host ("==> " + $Message) -ForegroundColor Cyan
}

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

function Ensure-StorageDirs {
    $incoming = Read-DotEnvValue -Name "APP_FILE_PATHS_INCOMING"
    $processed = Read-DotEnvValue -Name "APP_FILE_PATHS_PROCESSED"
    $errorsPath = Read-DotEnvValue -Name "APP_FILE_PATHS_ERROR"
    $quarantine = Read-DotEnvValue -Name "APP_FILE_PATHS_QUARANTINE"

    if (-not $incoming) { $incoming = "C:/vatm-storage/incoming" }
    if (-not $processed) { $processed = "C:/vatm-storage/processed" }
    if (-not $errorsPath) { $errorsPath = "C:/vatm-storage/error" }
    if (-not $quarantine) { $quarantine = "C:/vatm-storage/quarantine" }

    foreach ($dir in @($incoming, $processed, $errorsPath, $quarantine)) {
        if (-not (Test-Path $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
            Write-Host "Created $dir"
        }
    }
}

function Wait-TcpPort {
    param(
        [string] $TargetHost,
        [int] $Port,
        [int] $TimeoutSeconds
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $ok = Test-NetConnection -ComputerName $TargetHost -Port $Port `
            -WarningAction SilentlyContinue -ErrorAction SilentlyContinue
        if ($ok.TcpTestSucceeded) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Wait-OracleJdbc {
    param([int] $TimeoutSeconds)
    $user = Read-DotEnvValue -Name "SPRING_DATASOURCE_USERNAME"
    $pass = Read-DotEnvValue -Name "SPRING_DATASOURCE_PASSWORD"
    if (-not $user) { $user = "vatm_user" }
    if (-not $pass) { $pass = "vatm_password" }

    $connect = "${user}/${pass}@//localhost:1521/XEPDB1"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $output = docker exec aerosync-oracle bash -c "echo 'SELECT 1 FROM DUAL;' | sqlplus -s -L $connect" 2>&1
        if ($LASTEXITCODE -eq 0 -and $output -match "1") {
            return $true
        }
        Start-Sleep -Seconds 5
    }
    return $false
}

function Test-JarsPresent {
    foreach ($name in @("aerosync-worker", "aerosync-ingest", "aerosync-api")) {
        $jar = Join-Path $root "$name\target\$name-0.0.1-SNAPSHOT.jar"
        if (-not (Test-Path $jar)) { return $false }
    }
    return $true
}

function Start-AppWindow {
    param([string] $ScriptName)
    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    Start-Process -FilePath "powershell.exe" -WorkingDirectory $root -ArgumentList @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-File", "`"$scriptPath`""
    ) | Out-Null
}

function Stop-AerosyncApps {
    $stopped = 0
    Get-Process java -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
            if ($cmd -match "aerosync-(worker|ingest|api)") {
                Write-Host "Stopping PID $($_.Id)"
                Stop-Process -Id $_.Id -Force -ErrorAction Stop
                $stopped++
            }
        } catch {
            Write-Host "Could not stop PID $($_.Id): $_" -ForegroundColor Yellow
        }
    }
    if ($stopped -gt 0) {
        Start-Sleep -Seconds 2
    }
}

Set-Location $root

if ($Stop) {
    Write-Step -Message "Stopping Docker infrastructure"
    if (Test-Path $composeFile) {
        & docker compose -f $composeFile down
    }
    Write-Host "Close the worker / ingest / api PowerShell windows manually if still open."
    exit 0
}

Write-Host "VATM AeroSync - local run" -ForegroundColor Green
Write-Host "Repo: $root"

Write-Step -Message "Checking .env"
if (-not (Test-Path $envFile)) {
    if (-not (Test-Path $example)) {
        throw "Missing .env and .env.example"
    }
    Copy-Item $example $envFile
    Write-Host "Created .env from .env.example - edit it then run this script again."
    exit 1
}

$emailPassword = Read-DotEnvValue -Name "APP_EMAIL_PASSWORD"
$emailHost = Read-DotEnvValue -Name "APP_EMAIL_HOST"
if ($emailHost -and -not $emailPassword) {
    Write-Host "Note: APP_EMAIL_HOST is set but APP_EMAIL_PASSWORD is empty." -ForegroundColor Yellow
}

Write-Step -Message "Creating file storage folders"
Ensure-StorageDirs

if (-not $SkipDocker) {
    Write-Step -Message 'Starting Docker: Oracle, RabbitMQ, Redis'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker not found. Install Docker Desktop or use -SkipDocker."
    }
    & docker compose -f $composeFile up -d --wait

    Write-Host "Waiting for RabbitMQ on port 5672..."
    if (-not (Wait-TcpPort -TargetHost "localhost" -Port 5672 -TimeoutSeconds 120)) {
        throw "RabbitMQ did not become ready on port 5672"
    }
    Write-Host "Waiting for Redis on port 6379..."
    if (-not (Wait-TcpPort -TargetHost "localhost" -Port 6379 -TimeoutSeconds 60)) {
        throw "Redis did not become ready on port 6379"
    }

    Write-Host "Waiting for Oracle listener on port 1521..."
    if (-not (Wait-TcpPort -TargetHost "localhost" -Port 1521 -TimeoutSeconds 600)) {
        throw "Oracle listener did not open port 1521. Check: docker compose logs oracle-db"
    }
    Write-Host "Waiting for Oracle service XEPDB1 (first start may take several minutes)..."
    if (-not (Wait-OracleJdbc -TimeoutSeconds 600)) {
        throw "Oracle XEPDB1 is not accepting connections. Check: docker compose logs oracle-db"
    }
}

if ((-not $SkipBuild) -or (-not (Test-JarsPresent))) {
    Write-Step -Message "Stopping running AeroSync apps before build"
    Stop-AerosyncApps

    Write-Step -Message "Building applications with Maven"
    if (-not (Test-Path $mvnw)) { throw "Maven wrapper not found at $mvnw" }
    & $mvnw -f (Join-Path $root "pom.xml") clean package `
        "-Dmaven.test.skip=true" `
        -pl aerosync-ingest,aerosync-worker,aerosync-api -am
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
}

Write-Step -Message "Starting Java applications in new windows"
Start-AppWindow -ScriptName "start-worker.ps1"
Start-Sleep -Seconds 8
Start-AppWindow -ScriptName "start-ingest.ps1"
Start-Sleep -Seconds 3
Start-AppWindow -ScriptName "start-api.ps1"

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  API dashboard:  http://localhost:8080/api/dashboard/stats"
Write-Host "  RabbitMQ UI:    http://localhost:15672  (guest / guest)"
Write-Host "  Test file:      copy aerosync-worker\src\test\resources\samples\valid-flights.csv to incoming folder"
Write-Host ""
Write-Host "Stop:  .\run-aerosync.bat stop"
