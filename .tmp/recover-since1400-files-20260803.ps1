param(
    [long[]]$JobIds = @(1646, 1649, 1650, 1651, 1652),
    [string]$RecoverySubdirectory = 'since-1400-recovery-20260803'
)

$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8081'
$workspace = Split-Path -Parent $PSScriptRoot
$recoveryDirectory = Join-Path $PSScriptRoot $RecoverySubdirectory

$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $workspace '.env')) {
    if ($line -match '^([^#=]+)=(.*)$') {
        $values[$matches[1].Trim()] = $matches[2].Trim().Trim('"').Trim("'")
    }
}
function Resolve-ConfigValue([string]$name) {
    $value = $values[$name]
    while ($value -match '^\$\{([^}]+)\}$') { $value = $values[$matches[1]] }
    return $value
}

$errorRoot = [IO.Path]::GetFullPath((Resolve-ConfigValue 'APP_FILE_PATHS_ERROR'))
$errorPrefix = $errorRoot.TrimEnd('\') + '\'
$plan = @()
foreach ($jobId in $JobIds) {
    $job = Invoke-RestMethod "$api/api/jobs/$jobId"
    $record = $job.fileRecords | Sort-Object id -Descending | Select-Object -First 1
    $source = Get-ChildItem -LiteralPath $recoveryDirectory -File |
        Where-Object { $_.BaseName.Equals($record.checksum, [StringComparison]::OrdinalIgnoreCase) } |
        Select-Object -First 1
    if (-not $source) { throw "Recovered file not found for job $jobId." }
    $actualHash = (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $record.checksum.ToLowerInvariant()) {
        throw "SHA-256 mismatch for job $jobId."
    }
    $created = [datetime]$record.createdAt
    $directory = Join-Path (Join-Path (Join-Path $errorRoot $created.ToString('yyyy')) $created.ToString('MM')) $created.ToString('dd')
    $destination = [IO.Path]::GetFullPath((Join-Path $directory $record.storedFileName))
    if (-not $destination.StartsWith($errorPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe destination for job ${jobId}: $destination"
    }
    if (Test-Path -LiteralPath $destination) {
        throw "Destination already exists for job ${jobId}: $destination"
    }
    $plan += [pscustomobject]@{
        RecordId = [long]$record.id
        Source = $source.FullName
        Destination = $destination
    }
}

$copied = [Collections.Generic.List[object]]::new()
try {
    foreach ($item in $plan) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $item.Destination) -Force | Out-Null
        Copy-Item -LiteralPath $item.Source -Destination $item.Destination
        $copied.Add($item)
    }

    $url = Resolve-ConfigValue 'SPRING_DATASOURCE_URL'
    $username = Resolve-ConfigValue 'SPRING_DATASOURCE_USERNAME'
    $password = Resolve-ConfigValue 'SPRING_DATASOURCE_PASSWORD'
    $connect = $url -replace '^jdbc:oracle:thin:@//', ''
    $sql = [Collections.Generic.List[string]]::new()
    $sql.Add('whenever sqlerror exit sql.sqlcode rollback')
    foreach ($item in $copied) {
        $path = $item.Destination.Replace("'", "''")
        $sql.Add("update file_records set stored_path = '$path' where id = $($item.RecordId);")
    }
    $sql.Add('commit;')
    $sql.Add('exit success')
    $result = $sql -join [Environment]::NewLine |
        & 'E:\app\QT\product\12.1.0\dbhome_1\bin\sqlplus.exe' -L -s "$username/$password@$connect"
    if ($LASTEXITCODE -ne 0) { throw "Oracle path update failed. $result" }
} catch {
    foreach ($item in $copied) {
        if (Test-Path -LiteralPath $item.Destination -PathType Leaf) {
            Remove-Item -LiteralPath $item.Destination -Force
        }
    }
    throw
}

Write-Output "Recovered and SHA-256 verified $($copied.Count) files; database paths updated."
