$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8081'
$workspace = Split-Path -Parent $PSScriptRoot
$errorRoot = [IO.Path]::GetFullPath('D:/vatm-storage/error')

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
foreach ($page in 0..19) {
    $reports += (Invoke-RestMethod "$api/api/reports/emails?page=$page&size=100").content
}
$records = @{}
foreach ($report in ($reports | Where-Object syncJobId)) {
    $job = Invoke-RestMethod "$api/api/jobs/$($report.syncJobId)"
    foreach ($record in $job.fileRecords) { $records[[long]$record.id] = $record }
}

$plan = @()
foreach ($record in $records.Values) {
    $source = $record.storedPath
    if ($source -notmatch '^D:\\vatm-storage\\error\\\d{4}M\d{1,2}d\d{1,2}\\') { continue }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Malformed path is missing its file for record $($record.id): $source"
    }
    $created = [datetime]$record.createdAt
    $directory = Join-Path (Join-Path (Join-Path $errorRoot $created.ToString('yyyy')) $created.ToString('MM')) $created.ToString('dd')
    $destination = [IO.Path]::GetFullPath((Join-Path $directory ([IO.Path]::GetFileName($source))))
    $errorPrefix = $errorRoot.TrimEnd('\') + '\'
    if (-not $destination.StartsWith($errorPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe destination for record $($record.id): $destination"
    }
    if (Test-Path -LiteralPath $destination) {
        $base = [IO.Path]::GetFileNameWithoutExtension($destination)
        $extension = [IO.Path]::GetExtension($destination)
        $destination = Join-Path $directory ("{0}_record-{1}{2}" -f $base, $record.id, $extension)
    }
    $plan += [pscustomobject]@{ Id=[long]$record.id; Source=$source; Destination=$destination }
}

Write-Output "Malformed paths to correct: $($plan.Count)"
$moved = [Collections.Generic.List[object]]::new()
try {
    foreach ($item in $plan) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $item.Destination) -Force | Out-Null
        Move-Item -LiteralPath $item.Source -Destination $item.Destination
        $moved.Add($item)
        if (Test-Path -LiteralPath ($item.Source + '.log') -PathType Leaf) {
            Move-Item -LiteralPath ($item.Source + '.log') -Destination ($item.Destination + '.log')
        }
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
        if (Test-Path -LiteralPath ($item.Destination + '.log') -PathType Leaf) {
            Move-Item -LiteralPath ($item.Destination + '.log') -Destination ($item.Source + '.log')
        }
    }
    throw
}
Write-Output "Corrected physical and Oracle paths: $($moved.Count)"
