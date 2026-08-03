$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8081'
$workspace = Split-Path -Parent $PSScriptRoot
$recoveryDirectory = Join-Path $PSScriptRoot 'latest1000-missing-recovery-20260803'

$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $workspace '.env')) {
    if ($line -match '^([^#=]+)=(.*)$') { $values[$matches[1].Trim()] = $matches[2].Trim() }
}
function Resolve-Value([string]$name) {
    $value = $values[$name]
    while ($value -match '^\$\{([^}]+)\}$') { $value = $values[$matches[1]] }
    return $value
}

$reports = @()
foreach ($page in 0..9) {
    $reports += (Invoke-RestMethod "$api/api/reports/emails?page=$page&size=100").content
}
$terminal = @('FAILED', 'QUARANTINED', 'SKIPPED')
$seenJobs = @{}
$missing = @()
foreach ($report in ($reports | Where-Object { $_.syncJobId -and $_.processingStatus -in $terminal })) {
    if ($seenJobs.ContainsKey([string]$report.syncJobId)) { continue }
    $seenJobs[[string]$report.syncJobId] = $true
    $job = Invoke-RestMethod "$api/api/jobs/$($report.syncJobId)"
    $record = $job.fileRecords | Sort-Object id -Descending | Select-Object -First 1
    if ($record.processingStatus -in $terminal -and -not (Test-Path -LiteralPath $record.storedPath)) {
        $missing += $record
    }
}

$errorRoot = [IO.Path]::GetFullPath((Resolve-Value 'APP_FILE_PATHS_ERROR'))
$plan = @()
foreach ($record in $missing) {
    $matches = @(Get-ChildItem -LiteralPath $recoveryDirectory -File |
        Where-Object { $_.BaseName.Equals($record.checksum, [StringComparison]::OrdinalIgnoreCase) })
    if ($matches.Count -eq 0) { throw "No Gmail recovery file matches checksum for record $($record.id)." }
    $source = $matches[0].FullName
    $actualHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $record.checksum.ToLowerInvariant()) {
        throw "SHA-256 mismatch for record $($record.id)."
    }
    $created = [datetime]$record.createdAt
    $destinationDirectory = Join-Path (Join-Path (Join-Path $errorRoot $created.ToString('yyyy')) $created.ToString('MM')) $created.ToString('dd')
    $destination = Join-Path $destinationDirectory $record.storedFileName
    $destinationFull = [IO.Path]::GetFullPath($destination)
    $errorPrefix = $errorRoot.TrimEnd('\') + '\'
    if (-not $destinationFull.StartsWith($errorPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe destination for record $($record.id): $destinationFull"
    }
    if (Test-Path -LiteralPath $destination) {
        throw "Destination already exists for record $($record.id): $destination"
    }
    $plan += [pscustomobject]@{
        Id = [long]$record.id
        Source = $source
        Destination = $destinationFull
    }
}

$copied = [Collections.Generic.List[object]]::new()
try {
    foreach ($item in $plan) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $item.Destination) -Force | Out-Null
        Copy-Item -LiteralPath $item.Source -Destination $item.Destination
        $copied.Add($item)
    }
    $url = Resolve-Value 'SPRING_DATASOURCE_URL'
    $username = Resolve-Value 'SPRING_DATASOURCE_USERNAME'
    $password = Resolve-Value 'SPRING_DATASOURCE_PASSWORD'
    $connect = $url -replace '^jdbc:oracle:thin:@//', ''
    $sql = [Collections.Generic.List[string]]::new()
    $sql.Add('whenever sqlerror exit sql.sqlcode rollback')
    foreach ($item in $copied) {
        $path = $item.Destination.Replace("'", "''")
        $sql.Add("update file_records set stored_path = '$path' where id = $($item.Id);")
    }
    $sql.Add('commit;')
    $sql.Add('exit success')
    $output = $sql -join [Environment]::NewLine |
        & 'E:\app\QT\product\12.1.0\dbhome_1\bin\sqlplus.exe' -L -s "$username/$password@$connect"
    if ($LASTEXITCODE -ne 0) { throw "Oracle update failed. $output" }
} catch {
    foreach ($item in $copied) {
        if (Test-Path -LiteralPath $item.Destination -PathType Leaf) {
            Remove-Item -LiteralPath $item.Destination -Force
        }
    }
    throw
}

Write-Output "Restored from Gmail, SHA-256 verified, copied to error, and updated in Oracle: $($copied.Count)"
