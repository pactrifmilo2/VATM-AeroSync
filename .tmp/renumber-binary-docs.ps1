$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $root 'NGAY 31JUL_RENUMBERED_UNUSED_20260803'
$manifestPath = Join-Path $output 'RENUNBER_MANIFEST.csv'
$rows = @(Import-Csv -LiteralPath $manifestPath)
$pending = @($rows | Where-Object { $_.databaseStatus -eq 'PENDING_WORD_COM' })
$word = $null
$processed = 0
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    foreach ($row in $pending) {
        $path = Join-Path $output $row.file
        $document = $null
        try {
            $document = $word.Documents.Open($path)
            $range = $document.Content
            $find = $range.Find
            $find.ClearFormatting()
            $find.Replacement.ClearFormatting()
            $replaced = $find.Execute(
                    $row.oldPermit, $false, $false, $false, $false, $false,
                    $true, 0, $false, $row.newPermit, 1)
            if (-not $replaced) {
                throw "Permit text not found in $($row.file): $($row.oldPermit)"
            }
            $document.Save()
            $processed++
            $row.databaseStatus = 'NOT_FOUND_IN_ATFM_2026'
        }
        finally {
            if ($document) {
                $document.Close(0)
                [void][Runtime.InteropServices.Marshal]::ReleaseComObject($document)
            }
        }
    }
}
finally {
    if ($word) {
        $word.Quit()
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word)
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
$correctManifest = Join-Path $output 'RENUMBER_MANIFEST.csv'
$rows | Export-Csv -LiteralPath $correctManifest -NoTypeInformation -Encoding UTF8
"PROCESSED_DOC=$processed"
"MANIFEST=$correctManifest"
