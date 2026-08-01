param(
    [string]$SourceDirectory = "ngay0307_edited_9021",
    [string]$BackupDirectory,
    [string]$ReportPath = ".tmp/permit-number-edit-report.json"
)

$ErrorActionPreference = "Stop"

function Get-OrdinalFiles {
    param(
        [string]$Directory,
        [string]$Extension
    )

    $files = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
    Get-ChildItem -LiteralPath $Directory -File |
        Where-Object { $_.Extension -ieq $Extension } |
        ForEach-Object { $files.Add($_) }
    $files.Sort([System.Comparison[System.IO.FileInfo]]{
        param($left, $right)
        [StringComparer]::Ordinal.Compare($left.Name, $right.Name)
    })
    return $files
}

function Get-DocumentStructure {
    param($Document)

    $parts = [System.Collections.Generic.List[string]]::new()
    $parts.Add("paragraphs=$($Document.Paragraphs.Count)")
    $parts.Add("tables=$($Document.Tables.Count)")
    for ($index = 1; $index -le $Document.Tables.Count; $index++) {
        $table = $Document.Tables.Item($index)
        try {
            $rows = $table.Rows.Count
        } catch {
            $rows = "merged"
        }
        try {
            $columns = $table.Columns.Count
        } catch {
            $columns = "merged"
        }
        $parts.Add("table${index}:rows=$rows,columns=$columns,cells=$($table.Range.Cells.Count)")
        [Runtime.InteropServices.Marshal]::ReleaseComObject($table) | Out-Null
    }
    return $parts -join ";"
}

function Close-WordDocument {
    param($Document, [bool]$SaveChanges)

    if ($null -ne $Document) {
        $Document.Close($SaveChanges)
        [Runtime.InteropServices.Marshal]::ReleaseComObject($Document) | Out-Null
    }
}

$source = (Resolve-Path -LiteralPath $SourceDirectory).Path
$workspace = (Resolve-Path -LiteralPath ".").Path
if (-not $source.StartsWith($workspace, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Source directory must stay inside the workspace: $workspace"
}

$docxFiles = Get-OrdinalFiles -Directory $source -Extension ".docx"
$docFiles = Get-OrdinalFiles -Directory $source -Extension ".doc"
if ($docxFiles.Count -ne 53 -or $docFiles.Count -ne 9) {
    throw "Expected 53 DOCX and 9 DOC files, found $($docxFiles.Count) DOCX and $($docFiles.Count) DOC"
}

$items = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $docxFiles.Count; $index++) {
    $items.Add([pscustomobject]@{ File = $docxFiles[$index]; Expected = 9021 + $index })
}
for ($index = 0; $index -lt $docFiles.Count; $index++) {
    $items.Add([pscustomobject]@{ File = $docFiles[$index]; Expected = 9074 + $index })
}

if ([string]::IsNullOrWhiteSpace($BackupDirectory)) {
    $BackupDirectory = "${source}_backup_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
} elseif (-not [System.IO.Path]::IsPathRooted($BackupDirectory)) {
    $BackupDirectory = Join-Path $workspace $BackupDirectory
}
$backup = [System.IO.Path]::GetFullPath($BackupDirectory)
if (-not $backup.StartsWith($workspace, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Backup directory must stay inside the workspace: $workspace"
}
if (Test-Path -LiteralPath $backup) {
    throw "Backup directory already exists: $backup"
}

[System.IO.Directory]::CreateDirectory($backup) | Out-Null
foreach ($item in $items) {
    Copy-Item -LiteralPath $item.File.FullName -Destination (Join-Path $backup $item.File.Name)
}

$permitPattern = [regex]::new(
    "(?i)(?<type>LD|O\s*/?\s*F)\s*[-\u2013\u2014]?\s*(?<number>\d{1,5})(?=\s*/)",
    [Text.RegularExpressions.RegexOptions]::CultureInvariant)
$results = [System.Collections.Generic.List[object]]::new()
$word = $null
$failure = $null

try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0

    foreach ($item in $items) {
        $document = $null
        $verificationDocument = $null
        try {
            $wantedType = if ($item.File.Name -match "(?i)^(?:REV\d+\s+)?LD") { "LD" } else { "OF" }
            $hashBefore = (Get-FileHash -LiteralPath $item.File.FullName -Algorithm SHA256).Hash
            $document = $word.Documents.Open($item.File.FullName, $false, $false, $false)
            $structureBefore = Get-DocumentStructure -Document $document
            $contentStart = $document.Content.Start
            $found = $permitPattern.Matches($document.Content.Text)
            $valid = @($found | Where-Object {
                (($_.Groups["type"].Value -replace "\s|/", "").ToUpperInvariant()) -eq $wantedType
            })
            if ($valid.Count -eq 0) {
                throw "No $wantedType permit identity was found in $($item.File.Name)"
            }

            $currentMatch = $valid[0]
            $previous = $currentMatch.Groups["number"].Value
            $status = "HASH_REFRESHED"
            if ($previous -ne [string]$item.Expected) {
                $numberStart = $contentStart + $currentMatch.Groups["number"].Index
                $numberEnd = $numberStart + $currentMatch.Groups["number"].Length
                $numberRange = $document.Range($numberStart, $numberEnd)
                $numberRange.Text = [string]$item.Expected
                [Runtime.InteropServices.Marshal]::ReleaseComObject($numberRange) | Out-Null
                $status = "UPDATED"
            }

            # Force Word to write a fresh file even when its permit number was
            # already correct. This changes SHA-256 so email deduplication runs
            # the attachment again, without adding visible text or table rows.
            $document.Saved = $false
            $document.Save()
            Close-WordDocument -Document $document -SaveChanges $false
            $document = $null

            $verificationDocument = $word.Documents.Open($item.File.FullName, $false, $true, $false)
            $structureAfter = Get-DocumentStructure -Document $verificationDocument
            $verifiedMatches = $permitPattern.Matches($verificationDocument.Content.Text)
            $verified = @($verifiedMatches | Where-Object {
                (($_.Groups["type"].Value -replace "\s|/", "").ToUpperInvariant()) -eq $wantedType
            })
            if ($verified.Count -eq 0 -or $verified[0].Groups["number"].Value -ne [string]$item.Expected) {
                throw "Permit verification failed for $($item.File.Name); expected $($item.Expected)"
            }
            if ($structureAfter -ne $structureBefore) {
                throw "Document structure changed for $($item.File.Name)"
            }
            Close-WordDocument -Document $verificationDocument -SaveChanges $false
            $verificationDocument = $null

            $results.Add([pscustomobject]@{
                file = $item.File.Name
                expectedPermitNumber = $item.Expected
                previousPermitNumber = $previous
                status = $status
                structurePreserved = $true
                hashBefore = $hashBefore
                hashAfter = (Get-FileHash -LiteralPath $item.File.FullName -Algorithm SHA256).Hash
            })
            Write-Output ("{0} {1} -> {2} [{3}]" -f $item.File.Name, $previous, $item.Expected, $status)
        } finally {
            if ($null -ne $verificationDocument) {
                Close-WordDocument -Document $verificationDocument -SaveChanges $false
            }
            if ($null -ne $document) {
                Close-WordDocument -Document $document -SaveChanges $false
            }
        }
    }
} catch {
    $failure = $_
} finally {
    if ($null -ne $word) {
        $word.Quit()
        [Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}

if ($null -ne $failure) {
    foreach ($item in $items) {
        Copy-Item -LiteralPath (Join-Path $backup $item.File.Name) -Destination $item.File.FullName -Force
    }
    throw "Editing failed and all source files were restored from backup: $($failure.Exception.Message)"
}

$report = [pscustomobject]@{
    sourceDirectory = $source
    backupDirectory = $backup
    editedAt = (Get-Date).ToString("o")
    totalFiles = $results.Count
    updatedFiles = @($results | Where-Object status -eq "UPDATED").Count
    hashRefreshedFiles = @($results | Where-Object status -eq "HASH_REFRESHED").Count
    files = $results
}
$reportDirectory = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportDirectory)) {
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetFullPath($reportDirectory)) | Out-Null
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ReportPath -Encoding utf8

Write-Output "BACKUP=$backup"
Write-Output "REPORT=$([System.IO.Path]::GetFullPath($ReportPath))"
Write-Output "UPDATED=$($report.updatedFiles) HASH_REFRESHED=$($report.hashRefreshedFiles) TOTAL=$($report.totalFiles)"
