param(
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8081'
$workspace = Split-Path -Parent $PSScriptRoot

function Read-DotEnv {
    param([string]$Path)
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $parts = $line -split '=', 2
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }
    return $values
}

function Resolve-DotEnvValue {
    param([hashtable]$Values, [string]$Name)
    $value = $Values[$Name]
    $seen = @{}
    while ($value -match '^\$\{(?<name>[A-Za-z_][A-Za-z0-9_]*)\}$') {
        $referencedName = $matches['name']
        if ($seen.ContainsKey($referencedName) -or -not $Values.ContainsKey($referencedName)) {
            throw "Cannot resolve .env value $Name."
        }
        $seen[$referencedName] = $true
        $value = $Values[$referencedName]
    }
    return $value
}

function Is-UnderRoot {
    param([string]$Path, [string[]]$Roots)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    $resolved = [IO.Path]::GetFullPath($Path)
    foreach ($root in $Roots) {
        $rootPrefix = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
        if ($resolved.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

$envValues = Read-DotEnv (Join-Path $workspace '.env')
$errorRoot = $envValues['APP_FILE_PATHS_ERROR']
if ([string]::IsNullOrWhiteSpace($errorRoot)) { $errorRoot = 'C:/vatm-storage/error' }
$errorRoot = [IO.Path]::GetFullPath($errorRoot)

$allowedRoots = @(
    'C:/vatm-storage/error', 'C:/vatm-storage/processed', 'C:/vatm-storage/quarantine',
    'D:/vatm-storage/error', 'D:/vatm-storage/processed', 'D:/vatm-storage/quarantine'
) | ForEach-Object { [IO.Path]::GetFullPath($_) }

# Fetch the newest 1,000 report rows, using the same ordering as the report API.
$reports = @()
foreach ($page in 0..9) {
    $reports += (Invoke-RestMethod "$api/api/reports/emails?page=$page&size=100").content
}
if ($reports.Count -ne 1000) {
    throw "Expected exactly 1,000 newest report rows, received $($reports.Count)."
}

$terminal = @('FAILED', 'QUARANTINED', 'SKIPPED')
$candidateReports = $reports | Where-Object {
    $_.syncJobId -and $_.processingStatus -in $terminal
}

$fileRecords = @{}
foreach ($report in $candidateReports) {
    $job = Invoke-RestMethod "$api/api/jobs/$($report.syncJobId)"
    $record = $job.fileRecords |
        Sort-Object @{ Expression = 'createdAt'; Descending = $true },
                    @{ Expression = 'id'; Descending = $true } |
        Select-Object -First 1
    if ($null -ne $record -and $record.processingStatus -in $terminal) {
        $fileRecords[[long]$record.id] = $record
    }
}

$alreadyError = @()
$missing = @()
$movePlan = @()
foreach ($record in ($fileRecords.Values | Sort-Object id)) {
    $source = $record.storedPath
    if (-not (Is-UnderRoot $source $allowedRoots)) {
        throw "Refusing unsafe source path for file record $($record.id): $source"
    }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        $missing += $record
        continue
    }
    $sourceFull = [IO.Path]::GetFullPath($source)
    $sourceErrorRoot = $allowedRoots | Where-Object {
        $_.EndsWith('\error', [StringComparison]::OrdinalIgnoreCase) -and
        $sourceFull.StartsWith($_.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -First 1
    if ($sourceErrorRoot) {
        $alreadyError += $record
        continue
    }

    $created = if ($record.createdAt) { [datetime]$record.createdAt } else { Get-Date }
    $destinationDirectory = Join-Path (Join-Path (Join-Path $errorRoot $created.ToString('yyyy')) $created.ToString('MM')) $created.ToString('dd')
    $destination = Join-Path $destinationDirectory ([IO.Path]::GetFileName($sourceFull))
    if (Test-Path -LiteralPath $destination) {
        $base = [IO.Path]::GetFileNameWithoutExtension($destination)
        $extension = [IO.Path]::GetExtension($destination)
        $destination = Join-Path $destinationDirectory ("{0}_record-{1}{2}" -f $base, $record.id, $extension)
    }
    if (-not (Is-UnderRoot $destination @($errorRoot))) {
        throw "Refusing unsafe destination path for file record $($record.id): $destination"
    }
    $movePlan += [pscustomobject]@{
        Id = [long]$record.id
        Status = $record.processingStatus
        Source = $sourceFull
        Destination = [IO.Path]::GetFullPath($destination)
    }
}

Write-Output "Newest report rows: $($reports.Count)"
Write-Output "Unique terminal file records: $($fileRecords.Count)"
Write-Output "Already in an error directory: $($alreadyError.Count)"
Write-Output "Missing physical files: $($missing.Count)"
Write-Output "Files planned for move: $($movePlan.Count)"
$movePlan | Group-Object Status | Sort-Object Name |
    ForEach-Object { Write-Output ("  {0}: {1}" -f $_.Name, $_.Count) }

if (-not $Apply) {
    Write-Output 'DRY RUN ONLY. Re-run with -Apply to move files and update FILE_RECORDS.STORED_PATH.'
    return
}

$moved = [Collections.Generic.List[object]]::new()
try {
    foreach ($item in $movePlan) {
        $destinationDirectory = Split-Path -Parent $item.Destination
        New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        Move-Item -LiteralPath $item.Source -Destination $item.Destination
        $moved.Add($item)

        $sourceLog = $item.Source + '.log'
        if (Test-Path -LiteralPath $sourceLog -PathType Leaf) {
            Move-Item -LiteralPath $sourceLog -Destination ($item.Destination + '.log')
        }
    }

    if ($moved.Count -gt 0) {
        $url = Resolve-DotEnvValue $envValues 'SPRING_DATASOURCE_URL'
        $username = Resolve-DotEnvValue $envValues 'SPRING_DATASOURCE_USERNAME'
        $password = Resolve-DotEnvValue $envValues 'SPRING_DATASOURCE_PASSWORD'
        if ($url -notmatch '^jdbc:oracle:thin:@(?<connect>.+)$') {
            throw 'SPRING_DATASOURCE_URL is not a supported Oracle JDBC URL.'
        }
        $connect = $matches['connect'] -replace '^//', ''
        $sql = [Collections.Generic.List[string]]::new()
        $sql.Add('whenever sqlerror exit sql.sqlcode rollback')
        foreach ($item in $moved) {
            $path = $item.Destination.Replace("'", "''")
            $sql.Add("update file_records set stored_path = '$path' where id = $($item.Id);")
        }
        $sql.Add('commit;')
        $sql.Add('exit success')
        $sqlOutput = $sql -join [Environment]::NewLine |
            & 'E:\app\QT\product\12.1.0\dbhome_1\bin\sqlplus.exe' -L -s "$username/$password@$connect"
        if ($LASTEXITCODE -ne 0) {
            throw "Oracle update failed with exit code $LASTEXITCODE. $sqlOutput"
        }
    }
} catch {
    foreach ($item in ($moved | Select-Object -Last $moved.Count)) {
        if (Test-Path -LiteralPath $item.Destination -PathType Leaf) {
            Move-Item -LiteralPath $item.Destination -Destination $item.Source
        }
        $destinationLog = $item.Destination + '.log'
        if (Test-Path -LiteralPath $destinationLog -PathType Leaf) {
            Move-Item -LiteralPath $destinationLog -Destination ($item.Source + '.log')
        }
    }
    throw
}

Write-Output "Moved and updated successfully: $($moved.Count)"
