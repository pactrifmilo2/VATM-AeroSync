$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8081'
$messageId = '<CAE2ji_pJKvwn_4X55SsGhAVpfjQs7mGKGa4Rxf3M_3TYY6VpDw@mail.gmail.com>'
$workspace = Split-Path -Parent $PSScriptRoot
$recoveryDirectory = Join-Path $PSScriptRoot 'quyngoxuan-23-20260803'

$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $workspace '.env')) {
    if ($line -match '^([^#=]+)=(.*)$') {
        $values[$matches[1].Trim()] = $matches[2].Trim()
    }
}
function Resolve-Value([string]$name) {
    $value = $values[$name]
    while ($value -match '^\$\{([^}]+)\}$') { $value = $values[$matches[1]] }
    return $value
}

$errorRoot = [IO.Path]::GetFullPath((Resolve-Value 'APP_FILE_PATHS_ERROR'))
$reports = (Invoke-RestMethod "$api/api/reports/emails?sender=quyngoxuan%40gmail.com&page=0&size=100").content |
    Where-Object { $_.messageId -eq $messageId } |
    Sort-Object attachmentIndex
if ($reports.Count -ne 23) { throw "Expected 23 report rows, found $($reports.Count)." }

$plan = @()
foreach ($report in $reports) {
    $job = Invoke-RestMethod "$api/api/jobs/$($report.syncJobId)"
    $record = $job.fileRecords | Sort-Object id -Descending | Select-Object -First 1
    $safeAttachmentName = $report.attachmentName -replace '[\\/:*?"<>|]+', '_'
    $source = Join-Path $recoveryDirectory ("{0:D3}_{1}" -f $report.attachmentIndex, $safeAttachmentName)
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Recovered attachment is missing: $source"
    }
    $actualHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $record.checksum.ToLowerInvariant()) {
        throw "SHA-256 mismatch for attachment index $($report.attachmentIndex)."
    }
    $created = [datetime]$record.createdAt
    $directory = Join-Path (Join-Path (Join-Path $errorRoot $created.ToString('yyyy')) $created.ToString('MM')) $created.ToString('dd')
    $destination = Join-Path $directory $record.storedFileName
    if (Test-Path -LiteralPath $destination) {
        throw "Destination already exists; refusing to overwrite: $destination"
    }
    $plan += [pscustomobject]@{
        Id = [long]$record.id
        Source = [IO.Path]::GetFullPath($source)
        Destination = [IO.Path]::GetFullPath($destination)
    }
}

$moved = [Collections.Generic.List[object]]::new()
try {
    foreach ($item in $plan) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $item.Destination) -Force | Out-Null
        Move-Item -LiteralPath $item.Source -Destination $item.Destination
        $moved.Add($item)
    }

    $url = Resolve-Value 'SPRING_DATASOURCE_URL'
    $username = Resolve-Value 'SPRING_DATASOURCE_USERNAME'
    $password = Resolve-Value 'SPRING_DATASOURCE_PASSWORD'
    $connect = $url -replace '^jdbc:oracle:thin:@//', ''
    $sql = [Collections.Generic.List[string]]::new()
    $sql.Add('whenever sqlerror exit sql.sqlcode rollback')
    foreach ($item in $moved) {
        $path = $item.Destination.Replace("'", "''")
        $sql.Add("update file_records set stored_path = '$path' where id = $($item.Id);")
    }
    $sql.Add('commit;')
    $sql.Add('exit success')
    $output = $sql -join [Environment]::NewLine |
        & 'E:\app\QT\product\12.1.0\dbhome_1\bin\sqlplus.exe' -L -s "$username/$password@$connect"
    if ($LASTEXITCODE -ne 0) { throw "Oracle update failed. $output" }
} catch {
    foreach ($item in $moved) {
        if (Test-Path -LiteralPath $item.Destination -PathType Leaf) {
            Move-Item -LiteralPath $item.Destination -Destination $item.Source
        }
    }
    throw
}

Write-Output "Recovered, SHA-256 verified, moved to error, and updated in Oracle: $($moved.Count)"
