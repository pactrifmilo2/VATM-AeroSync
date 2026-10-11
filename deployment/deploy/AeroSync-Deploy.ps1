#Requires -Version 5.1
param(
    [ValidateSet("check", "deploy", "status", "restart", "rollback")]
    [string] $Action = "deploy",
    [string] $InstallRoot = "C:\AeroSync",
    [int] $HealthTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$serviceOrder = @("AeroSyncWorker", "AeroSyncIngest", "AeroSyncApi")
$stopOrder = @("AeroSyncIngest", "AeroSyncWorker", "AeroSyncApi")
$serviceApps = [ordered]@{
    AeroSyncWorker = "worker"
    AeroSyncIngest = "ingest"
    AeroSyncApi = "api"
}
$packageRoot = Split-Path -Parent $PSScriptRoot
$logFile = $null

function Write-Step {
    param([string] $Message)
    Write-Host ""
    Write-Host ("==> " + $Message) -ForegroundColor Cyan
}

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Write-CheckResult {
    param([bool] $Success, [string] $Name, [string] $Detail)
    $mark = if ($Success) { "[OK]" } else { "[X] " }
    $color = if ($Success) { "Green" } else { "Red" }
    Write-Host ("{0} {1,-20} {2}" -f $mark, $Name, $Detail) -ForegroundColor $color
}

function Read-DotEnvValues {
    param([string] $Path)
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*(?:#|$)') { continue }
        if ($line -match '^\s*([^=]+?)\s*=\s*(.*)\s*$') {
            $values[$Matches[1].Trim()] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

function Resolve-DotEnvValues {
    param([hashtable] $Values)
    $resolved = @{} + $Values
    for ($pass = 0; $pass -lt $Values.Count; $pass++) {
        $changed = $false
        foreach ($name in @($resolved.Keys)) {
            $value = [string]$resolved[$name]
            if ($value -match '^\$\{([A-Z][A-Z0-9_]*)\}$' -and $resolved.ContainsKey($Matches[1])) {
                $replacement = [string]$resolved[$Matches[1]]
                if ($replacement -ne $value) {
                    $resolved[$name] = $replacement
                    $changed = $true
                }
            }
        }
        if (-not $changed) { break }
    }
    return $resolved
}

function Invoke-RuntimeConfigMigration {
    param([string] $EnvironmentFile)

    $migration = Join-Path $packageRoot "tools\migrate-aerosync-runtime-config.sql"
    if (-not (Test-Path -LiteralPath $migration -PathType Leaf)) {
        throw "Goi deploy thieu migration runtime config: $migration"
    }

    $sqlPlus = Get-Command sqlplus.exe -ErrorAction Stop
    $values = Resolve-DotEnvValues (Read-DotEnvValues $EnvironmentFile)
    $username = [string]$values["SPRING_DATASOURCE_USERNAME"]
    $password = [string]$values["SPRING_DATASOURCE_PASSWORD"]
    $jdbc = [string]$values["SPRING_DATASOURCE_URL"]
    if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
        throw "Thieu SPRING_DATASOURCE_USERNAME hoac SPRING_DATASOURCE_PASSWORD trong config\.env."
    }
    if ($username -notmatch '^[A-Za-z][A-Za-z0-9_$#]*$') {
        throw "SPRING_DATASOURCE_USERNAME khong phai Oracle username hop le cho migration tu dong."
    }
    $databaseMatch = [regex]::Match(
        $jdbc,
        '^jdbc:oracle:thin:@//(?<host>[^:/]+):(?<port>\d+)/(?<service>[^/?]+)$')
    if (-not $databaseMatch.Success) {
        throw "SPRING_DATASOURCE_URL khong dung dinh dang jdbc:oracle:thin:@//host:port/service cho migration."
    }
    if ($password.Contains("`r") -or $password.Contains("`n") -or $password.Contains('"')) {
        throw "SPRING_DATASOURCE_PASSWORD co ky tu xuong dong hoac dau ngoac kep khong ho tro cho migration tu dong."
    }

    $connect = 'CONNECT {0}/"{1}"@//{2}:{3}/{4}' -f `
        $username, $password, $databaseMatch.Groups['host'].Value,
        $databaseMatch.Groups['port'].Value, $databaseMatch.Groups['service'].Value
    $sqlInput = @(
        "SET ECHO OFF",
        "SET DEFINE OFF",
        "WHENEVER OSERROR EXIT FAILURE ROLLBACK",
        "WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK",
        $connect,
        ('@"{0}"' -f $migration),
        "EXIT SUCCESS"
    ) -join "`n"

    Write-Step "Cap nhat schema runtime config Oracle"
    $output = @($sqlInput | & $sqlPlus.Source -L -S /nolog 2>&1)
    $exitCode = $LASTEXITCODE
    $validationReported = $false
    foreach ($line in $output) {
        $safeLine = [string]$line
        if ($password.Length -gt 0) { $safeLine = $safeLine.Replace($password, "[REDACTED]") }
        Write-Host $safeLine
        if ($safeLine -match 'Validated runtime allowlist schema\.') {
            $validationReported = $true
        }
    }
    $oracleArchiverError = @($output | Where-Object {
        ([string]$_) -match 'ORA-00257|Archiver error'
    })
    if ($oracleArchiverError.Count -gt 0) {
        throw "Oracle tra ORA-00257: archiver khong the ghi archive redo log. DBA can kiem tra FRA/archive destination va dung luong dia; khong co service nao bi dung."
    }
    if ($exitCode -ne 0 -or -not $validationReported) {
        throw "Oracle runtime-config migration failed with SQL*Plus exit code $exitCode. Services were not stopped."
    }
}

function Invoke-Preflight {
    Write-Step "Kiem tra moi truong truoc khi deploy"
    $script:preflightFailed = 0
    function Report([bool] $ok, [string] $name, [string] $detail) {
        Write-CheckResult -Success $ok -Name $name -Detail $detail
        if (-not $ok) { $script:preflightFailed++ }
    }

    Report (Test-Administrator) "Administrator" "Quyen cai dat Windows Service"
    Report ($PSVersionTable.PSVersion.Major -ge 5) "PowerShell" ($PSVersionTable.PSVersion.ToString())

    try {
        $java = Get-JavaExecutable
        Report $true "Java 21" $java
    } catch {
        Report $false "Java 21" $_.Exception.Message
    }

    $packageFiles = @("app\api.jar", "app\ingest.jar", "app\worker.jar",
        "ui\AeroSync.UI.exe", "ui\AeroSync.UI.pri", "deploy\Run-Service.ps1", "deploy\Open-AeroSync.ps1",
        "tools\WinSW-x64.exe", "tools\migrate-aerosync-runtime-config.sql", "SHA256SUMS.txt")
    foreach ($relative in $packageFiles) {
        $exists = Test-Path -LiteralPath (Join-Path $packageRoot $relative) -PathType Leaf
        Report $exists "Goi deploy" $relative
    }
    $sqlPlus = Get-Command sqlplus.exe -ErrorAction SilentlyContinue
    $sqlPlusDetail = if ($sqlPlus) { $sqlPlus.Source } else { "Khong tim thay sqlplus.exe" }
    Report ([bool]$sqlPlus) "Oracle SQL*Plus" $sqlPlusDetail

    foreach ($name in $serviceOrder) {
        $service = Get-Service -Name $name -ErrorAction SilentlyContinue
        $state = if ($service) { [string]$service.Status } else { "Chua cai dat" }
        Write-Host ("[--] {0,-20} {1}" -f $name, $state) -ForegroundColor Cyan
    }

    $installedEnv = Join-Path $InstallRoot "config\.env"
    $packageEnv = Join-Path $packageRoot ".env"
    $bundledEnv = Join-Path $packageRoot "config\.env"
    $envPath = if (Test-Path -LiteralPath $installedEnv) { $installedEnv }
        elseif (Test-Path -LiteralPath $packageEnv) { $packageEnv }
        elseif (Test-Path -LiteralPath $bundledEnv) { $bundledEnv }
        else { $null }
    $envDetail = if ($envPath) { $envPath } else { "Thieu config\.env da cau hinh" }
    Report ([bool]$envPath) "Cau hinh .env" $envDetail

    if ($envPath) {
        $values = Resolve-DotEnvValues (Read-DotEnvValues $envPath)
        foreach ($name in @("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD", "SERVER_PORT")) {
            $present = $values.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace($values[$name])
            Report $present "Bien cau hinh" $name
        }

        $jdbc = [string]$values["SPRING_DATASOURCE_URL"]
        if ($jdbc -match '@//(?<host>[^:/]+):(?<port>\d+)/(?<service>[^/?]+)') {
            $ok = Test-NetConnection -ComputerName $Matches.host -Port ([int]$Matches.port) `
                -InformationLevel Quiet -WarningAction SilentlyContinue
            Report $ok "Oracle" ("{0}:{1}/{2}" -f $Matches.host, $Matches.port, $Matches.service)
        } else {
            Report $false "Oracle" "SPRING_DATASOURCE_URL khong dung dinh dang"
        }

        foreach ($dependency in @(
            @{ Name = "RabbitMQ"; Host = "SPRING_RABBITMQ_HOST"; Port = "SPRING_RABBITMQ_PORT"; DefaultPort = 5672 },
            @{ Name = "Redis"; Host = "SPRING_DATA_REDIS_HOST"; Port = "SPRING_DATA_REDIS_PORT"; DefaultPort = 6379 }
        )) {
            $hostName = [string]$values[$dependency.Host]
            if ([string]::IsNullOrWhiteSpace($hostName)) { $hostName = "localhost" }
            $port = [int]$dependency.DefaultPort
            if ($values.ContainsKey($dependency.Port)) {
                $parsedPort = 0
                if ([int]::TryParse([string]$values[$dependency.Port], [ref]$parsedPort)) { $port = $parsedPort }
            }
            $ok = Test-NetConnection -ComputerName $hostName -Port $port `
                -InformationLevel Quiet -WarningAction SilentlyContinue
            Report $ok $dependency.Name ("{0}:{1}" -f $hostName, $port)
        }

        $serverPort = 8080
        if ($values.ContainsKey("SERVER_PORT")) {
            $parsedServerPort = 0
            if ([int]::TryParse([string]$values["SERVER_PORT"], [ref]$parsedServerPort)) {
                $serverPort = $parsedServerPort
            }
        }
        $listener = Get-NetTCPConnection -State Listen -LocalPort $serverPort -ErrorAction SilentlyContinue |
            Select-Object -First 1
        $api = Get-Service AeroSyncApi -ErrorAction SilentlyContinue
        $portOk = (-not $listener) -or ($api -and $api.Status -eq "Running")
        $detail = if (-not $listener) { "Cong $serverPort dang trong" }
            elseif ($portOk) { "Cong $serverPort dang do AeroSync hien tai su dung" }
            else { "Cong $serverPort da bi PID $($listener.OwningProcess) su dung" }
        Report $portOk "Cong API" $detail
    }

    $failed = $script:preflightFailed
    Remove-Variable preflightFailed -Scope Script -ErrorAction SilentlyContinue
    if ($failed -gt 0) {
        Write-Host ""
        Write-Host "[X] Co $failed muc chua dat. Deploy da dung truoc khi thay doi he thong." -ForegroundColor Red
        return $false
    }
    Write-Host ""
    Write-Host "[OK] Moi truong hop le. Bat dau deploy." -ForegroundColor Green
    return $true
}

function Stop-AeroSyncServices {
    foreach ($name in $stopOrder) {
        $service = Get-Service -Name $name -ErrorAction SilentlyContinue
        if (-not $service) {
            Write-Host ("[--] {0,-20} Chua cai dat" -f $name) -ForegroundColor DarkGray
            continue
        }
        if ($service.Status -eq "Stopped") {
            Write-Host ("[OK] {0,-20} Da dung" -f $name) -ForegroundColor Green
            continue
        }

        Write-Host ("[..] {0,-20} Dang dung (trang thai: {1})" -f $name, $service.Status) `
            -ForegroundColor Yellow
        try {
            Stop-Service -Name $name -Force -ErrorAction Stop
        } catch {
            # Services in StartPending can reject Stop-Service temporarily. sc.exe
            # sends the stop request without PowerShell's pending-state restriction.
            & sc.exe stop $name | Out-Null
        }

        $deadline = (Get-Date).AddSeconds(45)
        do {
            Start-Sleep -Milliseconds 500
            $service = Get-Service -Name $name -ErrorAction SilentlyContinue
            if (-not $service -or $service.Status -eq "Stopped") { break }
        } while ((Get-Date) -lt $deadline)

        if ($service -and $service.Status -ne "Stopped") {
            throw "Service $name khong dung duoc trong 45 giay (trang thai: $($service.Status)). Deploy chua cap nhat file."
        }
        Write-Host ("[OK] {0,-20} Da dung" -f $name) -ForegroundColor Green
    }
}

function Start-AeroSyncServices {
    foreach ($name in $serviceOrder) {
        $service = Get-Service -Name $name -ErrorAction Stop
        if ($service.Status -ne "Running") {
            Write-Host "Starting $name..."
            Start-Service -Name $name
            $deadline = (Get-Date).AddSeconds(45)
            do {
                $service.Refresh()
                if ($service.Status -eq "Running") { break }
                if ($service.Status -eq "Stopped") {
                    $app = [string]$serviceApps[$name]
                    $outLog = Join-Path $InstallRoot "logs\$app\AeroSync$($app.Substring(0,1).ToUpperInvariant())$($app.Substring(1)).out.log"
                    $hint = if (Test-Path -LiteralPath $outLog) {
                        (Get-Content -LiteralPath $outLog -Tail 12 -ErrorAction SilentlyContinue) -join "`n"
                    } else { "Khong co log $outLog" }
                    throw "Service $name da dung ngay sau khi khoi dong.`n$hint"
                }
                Start-Sleep -Milliseconds 500
            } while ((Get-Date) -lt $deadline)
            $service.Refresh()
            if ($service.Status -ne "Running") {
                throw "Khong the khoi dong service $name trong 45 giay (trang thai: $($service.Status))."
            }
        }
    }
}

function Get-JavaVersionText {
    param([string] $JavaExecutable)

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $JavaExecutable
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { return $null }
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { return $null }
        return ($standardOutput + [Environment]::NewLine + $standardError).Trim()
    } catch {
        return $null
    } finally {
        $process.Dispose()
    }
}

function Get-JavaExecutable {
    $candidates = @()
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\java.exe") }
    $candidates += "D:\Java\bin\java.exe"
    $candidates += "C:\Java\bin\java.exe"
    $candidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Recurse -Filter java.exe `
        -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $version = Get-JavaVersionText -JavaExecutable $candidate
        if ($version -match '(?m)\bversion\s+"21(?:\.|\")') {
            return (Resolve-Path $candidate).Path
        }
    }
    throw "Khong tim thay Java 21. Hay cai Java 21 roi chay lai file nay."
}

function Find-LegacyEnvironment {
    $candidates = @(
        (Join-Path $packageRoot ".env"),
        (Join-Path (Get-Location) ".env")
    )
    foreach ($serviceName in $serviceOrder) {
        $service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
        if (-not $service -or $service.PathName -notmatch '"([^"]+\.xml)"') { continue }
        $xmlPath = $Matches[1]
        if (-not (Test-Path -LiteralPath $xmlPath -PathType Leaf)) { continue }
        try {
            [xml] $xml = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8
            if ($xml.service.workingdirectory) {
                $candidates += Join-Path ([string]$xml.service.workingdirectory) ".env"
            }
        } catch {
            Write-Warning "Could not inspect legacy service config $xmlPath"
        }
    }
    return $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -First 1
}

function Ensure-Environment {
    $target = Join-Path $InstallRoot "config\.env"
    if (Test-Path -LiteralPath $target -PathType Leaf) { return }
    $legacy = Find-LegacyEnvironment
    if ($legacy) {
        Copy-Item -LiteralPath $legacy -Destination $target
        Write-Host "Da chuyen cau hinh tu ban cai cu."
        return
    }
    $bundled = Join-Path $packageRoot "config\.env"
    if (Test-Path -LiteralPath $bundled -PathType Leaf) {
        Copy-Item -LiteralPath $bundled -Destination $target
        return
    }
    $example = Join-Path $packageRoot "config\.env.example"
    if (Test-Path -LiteralPath $example -PathType Leaf) {
        Copy-Item -LiteralPath $example -Destination $target
    }
    throw "Chua co cau hinh .env. Ky thuat can cau hinh mot lan tai $target, sau do chay lai."
}

function New-ServiceXml {
    param([string] $ServiceName, [string] $App)
    $display = "VATM AeroSync " + (Get-Culture).TextInfo.ToTitleCase($App)
    $powershell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $runner = Join-Path $InstallRoot "deploy\Run-Service.ps1"
    $logPath = Join-Path $InstallRoot "logs\$App"
    $escapedPowerShell = [System.Security.SecurityElement]::Escape($powershell)
    $escapedRunner = [System.Security.SecurityElement]::Escape($runner)
    $escapedRoot = [System.Security.SecurityElement]::Escape($InstallRoot)
    $escapedLogPath = [System.Security.SecurityElement]::Escape($logPath)
    return @"
<service>
  <id>$ServiceName</id>
  <name>$display</name>
  <description>VATM AeroSync $App service</description>
  <executable>$escapedPowerShell</executable>
  <arguments>-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File &quot;$escapedRunner&quot; -App $App</arguments>
  <workingdirectory>$escapedRoot</workingdirectory>
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <hidewindow>true</hidewindow>
  <logpath>$escapedLogPath</logpath>
  <log mode="roll"></log>
  <onfailure action="restart" delay="15 sec"/>
  <resetfailure>1 hour</resetfailure>
  <stoptimeout>45 sec</stoptimeout>
</service>
"@
}

function Test-PackageChecksums {
    $checksumFile = Join-Path $packageRoot "SHA256SUMS.txt"
    if (-not (Test-Path -LiteralPath $checksumFile -PathType Leaf)) {
        throw "Goi cap nhat thieu SHA256SUMS.txt."
    }
    foreach ($line in Get-Content -LiteralPath $checksumFile -Encoding ASCII) {
        if ($line -notmatch '^(?<hash>[A-Fa-f0-9]{64})\s{2}(?<path>.+)$') {
            throw "Dong checksum khong hop le: $line"
        }
        $relative = $Matches.path
        $expected = $Matches.hash
        $file = Join-Path $packageRoot $relative
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw "Goi cap nhat thieu file: $relative"
        }
        $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
        if ($actual -ne $expected) {
            throw "File bi loi hoac chua copy xong: $relative"
        }
    }
}

function Install-Or-RefreshServices {
    $winsw = Join-Path $InstallRoot "tools\WinSW-x64.exe"
    foreach ($entry in $serviceApps.GetEnumerator()) {
        $name = [string]$entry.Key
        $app = [string]$entry.Value
        $xmlPath = Join-Path $InstallRoot "services\$name.xml"
        New-ServiceXml -ServiceName $name -App $app |
            Set-Content -LiteralPath $xmlPath -Encoding UTF8

        $existing = Get-CimInstance Win32_Service -Filter "Name='$name'" -ErrorAction SilentlyContinue
        $managedPath = '"' + $winsw + '"'
        if ($existing -and -not $existing.PathName.StartsWith($managedPath, [StringComparison]::OrdinalIgnoreCase)) {
            & sc.exe delete $name | Out-Null
            $deadline = (Get-Date).AddSeconds(30)
            while ((Get-Date) -lt $deadline -and (Get-Service -Name $name -ErrorAction SilentlyContinue)) {
                Start-Sleep -Milliseconds 500
            }
            $existing = $null
        }
        if ($existing) {
            & $winsw refresh $xmlPath | Out-Null
        } else {
            & $winsw install $xmlPath | Out-Null
        }
        if ($LASTEXITCODE -ne 0) { throw "Khong the cai dat service $name." }
    }
}

function Get-ServerPort {
    $envFile = Join-Path $InstallRoot "config\.env"
    $line = Get-Content -LiteralPath $envFile -Encoding UTF8 |
        Where-Object { $_ -match '^\s*SERVER_PORT\s*=' } | Select-Object -Last 1
    if ($line -and $line -match '=\s*(\d+)\s*$') { return [int]$Matches[1] }
    return 8080
}

function Test-ServerPortAvailable {
    $port = Get-ServerPort
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -ne $PID } | Select-Object -First 1
    if ($listener) {
        $api = Get-Service AeroSyncApi -ErrorAction SilentlyContinue
        if ($api -and $api.Status -eq "Running") { return }
        $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        $ownerName = if ($owner) { $owner.ProcessName } else { "PID $($listener.OwningProcess)" }
        throw "Cong $port da bi su dung boi $ownerName (PID $($listener.OwningProcess)). Hay doi SERVER_PORT trong $InstallRoot\config\.env."
    }
}

function Wait-Healthy {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    $port = Get-ServerPort
    $uri = "http://localhost:$port/api/dashboard/stats"
    while ((Get-Date) -lt $deadline) {
        foreach ($name in $serviceOrder) {
            $service = Get-Service -Name $name -ErrorAction Stop
            if ($service.Status -ne 'Running') {
                $app = [string]$serviceApps[$name]
                $outLog = Join-Path $InstallRoot "logs\$app\$name.out.log"
                $hint = if (Test-Path -LiteralPath $outLog) {
                    (Get-Content -LiteralPath $outLog -Tail 20 -ErrorAction SilentlyContinue) -join "`n"
                } else { "Khong co log $outLog" }
                throw "Service $name da dung (trang thai: $($service.Status)).`n$hint"
            }
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 5 -ErrorAction Stop
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                # WinSW can report Running before the Java child has finished startup.
                # Recheck all services after a short stabilization window.
                $stableUntil = (Get-Date).AddSeconds(15)
                while ((Get-Date) -lt $stableUntil) {
                    $stopped = @($serviceOrder | Where-Object {
                        (Get-Service -Name $_ -ErrorAction Stop).Status -ne 'Running'
                    })
                    if ($stopped.Count -gt 0) {
                        throw "Service stopped after API startup: $($stopped -join ', ')"
                    }
                    Start-Sleep -Seconds 2
                }
                return
            }
        } catch {
            if ($_.Exception.Message -like 'Service stopped after API startup:*') { throw }
        }
        Start-Sleep -Seconds 3
    }
    throw "API khong san sang tai $uri sau $HealthTimeoutSeconds giay; kiem tra log va SERVER_PORT."

}

function Show-Status {
    foreach ($name in $serviceOrder) {
        $service = Get-Service -Name $name -ErrorAction SilentlyContinue
        $state = if ($service) { $service.Status } else { "NotInstalled" }
        Write-Host ("{0,-20} {1}" -f $name, $state)
    }
    $versionFile = Join-Path $InstallRoot "VERSION"
    if (Test-Path -LiteralPath $versionFile) {
        Write-Host ("Version: " + (Get-Content -LiteralPath $versionFile -Raw).Trim())
    }
}

function Install-UiShortcut {
    $launcher = Join-Path $InstallRoot "deploy\Open-AeroSync.ps1"
    $uiExe = Join-Path $InstallRoot "ui\AeroSync.UI.exe"
    $powershell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    $arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$launcher`""
    $shortcutPaths = @(
        (Join-Path ([Environment]::GetFolderPath("CommonDesktopDirectory")) "AeroSync.lnk"),
        (Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "AeroSync.lnk")
    )
    $shell = New-Object -ComObject WScript.Shell
    foreach ($shortcutPath in $shortcutPaths) {
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = $powershell
        $shortcut.Arguments = $arguments
        $shortcut.WorkingDirectory = $InstallRoot
        $shortcut.IconLocation = "$uiExe,0"
        $shortcut.Description = "VATM AeroSync"
        $shortcut.Save()
    }
}

function Open-AeroSyncUi {
    $launcher = Join-Path $InstallRoot "deploy\Open-AeroSync.ps1"
    $powershell = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
    Start-Process -FilePath $powershell -ArgumentList @(
        "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $launcher
    ) -WindowStyle Hidden
}

function Restore-Backup {
    param([string] $BackupPath)
    if (-not $BackupPath) {
        $BackupPath = Get-ChildItem (Join-Path $InstallRoot "releases") -Directory `
            -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $BackupPath -or -not (Test-Path -LiteralPath (Join-Path $BackupPath "app"))) {
        throw "Khong co ban cu de rollback."
    }
    Stop-AeroSyncServices
    $current = Join-Path $InstallRoot "app"
    if (Test-Path -LiteralPath $current) { Remove-Item -LiteralPath $current -Recurse -Force }
    Copy-Item -LiteralPath (Join-Path $BackupPath "app") -Destination $current -Recurse
    if (Test-Path -LiteralPath (Join-Path $BackupPath "VERSION")) {
        Copy-Item -LiteralPath (Join-Path $BackupPath "VERSION") `
            -Destination (Join-Path $InstallRoot "VERSION") -Force
    }
    Install-Or-RefreshServices
    Start-AeroSyncServices
    Wait-Healthy
}

if ($Action -eq "status") { Show-Status; exit 0 }
if (-not (Test-Administrator)) {
    throw "Hay chay AeroSync-Deploy.cmd; file nay se tu xin quyen Administrator."
}
if ($Action -eq "check") {
    if (Invoke-Preflight) { exit 0 } else { exit 1 }
}
if ($Action -eq "restart") {
    Stop-AeroSyncServices
    Start-AeroSyncServices
    Wait-Healthy
    Show-Status
    exit 0
}
if ($Action -eq "rollback") {
    Write-Step "Khoi phuc phien ban truoc"
    Restore-Backup
    Show-Status
    exit 0
}

if (-not (Invoke-Preflight)) { exit 1 }

$required = @("api.jar", "ingest.jar", "worker.jar")
foreach ($file in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $packageRoot "app\$file") -PathType Leaf)) {
        throw "Goi cap nhat thieu app\$file."
    }
}
$packageWinSw = Join-Path $packageRoot "tools\WinSW-x64.exe"
if (-not (Test-Path -LiteralPath $packageWinSw -PathType Leaf)) {
    throw "Goi cap nhat thieu tools\WinSW-x64.exe."
}
$packageRuntimeMigration = Join-Path $packageRoot "tools\migrate-aerosync-runtime-config.sql"
if (-not (Test-Path -LiteralPath $packageRuntimeMigration -PathType Leaf)) {
    throw "Goi cap nhat thieu tools\migrate-aerosync-runtime-config.sql."
}
Write-Step "Kiem tra tinh toan ven cua goi cap nhat"
Test-PackageChecksums

New-Item -ItemType Directory -Force -Path $InstallRoot,
    (Join-Path $InstallRoot "app"), (Join-Path $InstallRoot "config"),
    (Join-Path $InstallRoot "deploy"), (Join-Path $InstallRoot "logs"),
    (Join-Path $InstallRoot "releases"), (Join-Path $InstallRoot "runtime"),
    (Join-Path $InstallRoot "services"), (Join-Path $InstallRoot "tools"),
    (Join-Path $InstallRoot "ui") | Out-Null
$logFile = Join-Path $InstallRoot ("logs\deploy-{0}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
Start-Transcript -Path $logFile | Out-Null

$backupPath = $null
try {
    Write-Step "Kiem tra Java va cau hinh"
    $java = Get-JavaExecutable
    Set-Content -LiteralPath (Join-Path $InstallRoot "runtime\java-path.txt") `
        -Value $java -Encoding UTF8
    Ensure-Environment
    Test-ServerPortAvailable

    Invoke-RuntimeConfigMigration -EnvironmentFile (Join-Path $InstallRoot "config\.env")

    Write-Step "Dung cac service AeroSync"
    Stop-AeroSyncServices

    $currentApp = Join-Path $InstallRoot "app"
    if ((Get-ChildItem -LiteralPath $currentApp -File -ErrorAction SilentlyContinue).Count -gt 0) {
        $backupPath = Join-Path $InstallRoot ("releases\{0}" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
        New-Item -ItemType Directory -Path $backupPath | Out-Null
        Copy-Item -LiteralPath $currentApp -Destination (Join-Path $backupPath "app") -Recurse
        $installedVersion = Join-Path $InstallRoot "VERSION"
        if (Test-Path -LiteralPath $installedVersion) {
            Copy-Item -LiteralPath $installedVersion -Destination (Join-Path $backupPath "VERSION")
        }
    }

    Write-Step "Cap nhat ung dung"
    foreach ($file in $required) {
        Copy-Item -LiteralPath (Join-Path $packageRoot "app\$file") `
            -Destination (Join-Path $currentApp $file) -Force
    }
    Copy-Item -LiteralPath (Join-Path $packageRoot "deploy\Run-Service.ps1") `
        -Destination (Join-Path $InstallRoot "deploy\Run-Service.ps1") -Force
    Copy-Item -LiteralPath (Join-Path $packageRoot "deploy\Open-AeroSync.ps1") `
        -Destination (Join-Path $InstallRoot "deploy\Open-AeroSync.ps1") -Force
    Copy-Item -Path (Join-Path $packageRoot "ui\*") `
        -Destination (Join-Path $InstallRoot "ui") -Recurse -Force
    Copy-Item -LiteralPath $packageWinSw `
        -Destination (Join-Path $InstallRoot "tools\WinSW-x64.exe") -Force
    Copy-Item -LiteralPath $packageRuntimeMigration `
        -Destination (Join-Path $InstallRoot "tools\migrate-aerosync-runtime-config.sql") -Force
    $packageVersion = Join-Path $packageRoot "VERSION"
    if (Test-Path -LiteralPath $packageVersion) {
        Copy-Item -LiteralPath $packageVersion -Destination (Join-Path $InstallRoot "VERSION") -Force
    }

    Write-Step "Cai dat va khoi dong service"
    Install-Or-RefreshServices
    Start-AeroSyncServices
    Wait-Healthy
    Install-UiShortcut

    Write-Step "Trien khai thanh cong"
    Show-Status
    Write-Host "Thu muc cai dat: $InstallRoot"
    Open-AeroSyncUi
} catch {
    Write-Host ""
    Write-Host ("LOI: " + $_.Exception.Message) -ForegroundColor Red
    if ($backupPath) {
        Write-Host "Dang tu dong khoi phuc phien ban truoc..." -ForegroundColor Yellow
        try {
            Restore-Backup -BackupPath $backupPath
            Write-Host "Da khoi phuc phien ban truoc." -ForegroundColor Yellow
        } catch {
            Write-Host ("Rollback cung bi loi: " + $_.Exception.Message) -ForegroundColor Red
        }
    }
    Write-Host "Nhat ky: $logFile"
    exit 1
} finally {
    Stop-Transcript -ErrorAction SilentlyContinue | Out-Null
}
