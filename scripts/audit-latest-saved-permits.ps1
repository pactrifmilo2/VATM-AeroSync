param(
    [ValidateRange(1, 1000)]
    [int] $Limit = 100,
    [string] $OutputPath,
    [string] $ManifestPath
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Missing .env at repository root."
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $root ("reports\latest-{0}-saved-permits-{1}.md" -f $Limit, (Get-Date -Format "yyyy-MM-dd"))
}
if (-not $ManifestPath) {
    $ManifestPath = [IO.Path]::ChangeExtension($OutputPath, ".manifest.txt")
}

$envValues = @{}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$') {
        $envValues[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}

function Resolve-EnvValue {
    param([string] $Name)
    $value = $envValues[$Name]
    if ($value -match '^\$\{([A-Z][A-Z0-9_]*)\}$') {
        return $envValues[$Matches[1]]
    }
    return $value
}

function New-OracleConnectString {
    param([string] $Prefix)
    $url = Resolve-EnvValue "${Prefix}_URL"
    if ($url -notmatch '@//([^:/]+):(\d+)/(.+)$') {
        throw "${Prefix}_URL is not a supported Oracle JDBC URL."
    }
    $dbHost = $Matches[1]
    $dbPort = $Matches[2]
    $dbService = $Matches[3]
    $dbUser = Resolve-EnvValue "${Prefix}_USERNAME"
    $dbPassword = Resolve-EnvValue "${Prefix}_PASSWORD"
    if (-not $dbUser -or $null -eq $dbPassword) {
        throw "Missing credentials for $Prefix."
    }
    return "$dbUser/`"$($dbPassword.Replace('"', '""'))`"@$dbHost`:$dbPort/$dbService"
}

function Invoke-OracleExport {
    param(
        [string] $Connect,
        [string] $Sql,
        [string] $OutputFile
    )
    $script = @"
WHENEVER SQLERROR EXIT SQL.SQLCODE
CONNECT $Connect
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF VERIFY OFF ECHO OFF LINESIZE 32767 TRIMSPOOL ON
SPOOL "$OutputFile"
$Sql
SPOOL OFF
EXIT
"@
    $result = $script | & sqlplus.exe -L -S /nolog 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "SQLPlus export failed: $($result -join ' ')"
    }
}

function Sql-Text {
    param([string] $Expression)
    return "REPLACE(REPLACE(REPLACE(REPLACE(NVL($Expression, ''), CHR(13), ' '), CHR(10), ' '), '|~|', ' '), CHR(9), ' ')"
}

function Read-DelimitedRows {
    param([string] $Path, [string[]] $Columns)
    foreach ($line in Get-Content -LiteralPath $Path) {
        if (-not $line.Contains('|~|')) { continue }
        $parts = $line -split '\|~\|', $Columns.Count
        if ($parts.Count -ne $Columns.Count) { continue }
        $row = [ordered]@{}
        for ($index = 0; $index -lt $Columns.Count; $index++) {
            $row[$Columns[$index]] = $parts[$index].Trim()
        }
        [pscustomobject]$row
    }
}

function Read-DocxText {
    param([string] $Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry("word/document.xml")
        if (-not $entry) { throw "Missing word/document.xml." }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $xml = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $plain = $xml `
            -replace '</w:tc>', "`n" `
            -replace '</w:tr>', "`n" `
            -replace '</w:p>', "`n" `
            -replace '<w:tab[^>]*/>', "`t" `
            -replace '<[^>]+>', ''
        return [System.Net.WebUtility]::HtmlDecode($plain)
    } finally {
        $archive.Dispose()
    }
}

function Markdown-Cell {
    param($Value)
    if ($null -eq $Value) { return "" }
    return ($Value.ToString() -replace '\|', '\|' -replace '[\r\n]+', ' ').Trim()
}

$trackingConnect = New-OracleConnectString "SPRING_DATASOURCE"
$targetConnect = New-OracleConnectString "APP_ATFM_DATASOURCE"
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("aerosync-saved-audit-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tempDir | Out-Null

try {
    $trackingPath = Join-Path $tempDir "tracking.txt"
    $trackingColumns = @(
        "ImportId", "SyncJobId", "PermitId", "TargetMasterId", "TargetPermId",
        "ExpectedDetailCount", "TargetWriteCount", "ImportedAt", "StoredPath", "OriginalFileName",
        "AttachmentName", "Subject"
    )
    $trackingFields = @(
        (Sql-Text "TO_CHAR(import_id)"),
        (Sql-Text "TO_CHAR(sync_job_id)"),
        (Sql-Text "normalized_permit_id"),
        (Sql-Text "TO_CHAR(target_master_id)"),
        (Sql-Text "TO_CHAR(target_perm_id)"),
        (Sql-Text "TO_CHAR(detail_count)"),
        (Sql-Text "TO_CHAR(target_write_count)"),
        (Sql-Text "TO_CHAR(imported_at, 'YYYY-MM-DD HH24:MI:SS')"),
        (Sql-Text "stored_path"),
        (Sql-Text "original_file_name"),
        (Sql-Text "attachment_name"),
        (Sql-Text "subject")
    ) -join " || '|~|' || "
    $trackingSql = @"
WITH saved_import AS (
    SELECT pi.*,
           COUNT(*) OVER (PARTITION BY pi.TARGET_MASTER_ID, pi.TARGET_PERM_ID) AS target_write_count
      FROM PERMIT_IMPORTS pi
     WHERE pi.STATUS = 'SAVED'
), latest_import AS (
    SELECT pi.*, ROW_NUMBER() OVER (ORDER BY pi.UPDATED_AT DESC, pi.ID DESC) AS audit_rn
      FROM saved_import pi
), latest_file AS (
    SELECT fr.*, ROW_NUMBER() OVER (PARTITION BY fr.SYNC_JOB_ID ORDER BY fr.ID DESC) AS file_rn
      FROM FILE_RECORDS fr
), latest_email AS (
    SELECT em.*, ROW_NUMBER() OVER (PARTITION BY em.SYNC_JOB_ID ORDER BY em.ID DESC) AS email_rn
      FROM EMAIL_METADATA em
)
SELECT $trackingFields
  FROM (
        SELECT pi.ID AS import_id, pi.SYNC_JOB_ID AS sync_job_id,
               pi.NORMALIZED_PERMIT_ID AS normalized_permit_id,
               pi.TARGET_MASTER_ID AS target_master_id, pi.TARGET_PERM_ID AS target_perm_id,
               pi.DETAIL_COUNT AS detail_count, pi.target_write_count, pi.UPDATED_AT AS imported_at,
               fr.STORED_PATH AS stored_path, fr.ORIGINAL_FILE_NAME AS original_file_name,
               em.ATTACHMENT_NAME AS attachment_name, em.SUBJECT AS subject
          FROM latest_import pi
          LEFT JOIN latest_file fr ON fr.SYNC_JOB_ID = pi.SYNC_JOB_ID AND fr.file_rn = 1
          LEFT JOIN latest_email em ON em.SYNC_JOB_ID = pi.SYNC_JOB_ID AND em.email_rn = 1
         WHERE pi.audit_rn <= $Limit
         ORDER BY pi.audit_rn
       );
"@
    Invoke-OracleExport $trackingConnect $trackingSql $trackingPath
    $trackingRows = @(Read-DelimitedRows $trackingPath $trackingColumns)
    if ($trackingRows.Count -ne $Limit) {
        throw "Expected $Limit SAVED imports but tracking export returned $($trackingRows.Count)."
    }

    $masterIds = ($trackingRows.TargetMasterId | Where-Object { $_ -match '^\d+$' } | Sort-Object -Unique) -join ','
    if (-not $masterIds) { throw "The selected imports do not contain target master IDs." }

    $targetPath = Join-Path $tempDir "target.txt"
    $targetColumns = @(
        "TableKind", "MasterId", "PermId", "PermitNumber", "Reference", "BillingAddress",
        "Version", "PermitContentLength", "DetailCount", "InvalidCoreCount", "RemarkCount"
    )
    $targetFields = @(
        (Sql-Text "table_kind"), (Sql-Text "TO_CHAR(master_id)"), (Sql-Text "TO_CHAR(perm_id)"),
        (Sql-Text "permit_number"), (Sql-Text "reference_value"), (Sql-Text "billing_address"),
        (Sql-Text "version_value"), (Sql-Text "TO_CHAR(content_length)"),
        (Sql-Text "TO_CHAR(detail_count)"), (Sql-Text "TO_CHAR(invalid_core_count)"),
        (Sql-Text "TO_CHAR(remark_count)")
    ) -join " || '|~|' || "
    $targetSql = @"
WITH target_rows AS (
    SELECT 'SC' AS table_kind, m.ID AS master_id, m.PERM_ID AS perm_id,
           m.PERMNBR_ID AS permit_number, m.REFERENCE AS reference_value,
           m.BILLINGADDRESS AS billing_address, m.VERSION AS version_value,
           CASE WHEN m.PERMCONTENT1 IS NULL THEN 0 ELSE DBMS_LOB.GETLENGTH(m.PERMCONTENT1) END AS content_length,
           COUNT(d.ID) AS detail_count,
           SUM(CASE WHEN d.ID IS NOT NULL AND (TRIM(d.FLIGHTNBR) IS NULL OR TRIM(d.FROM_AIRP) IS NULL OR TRIM(d.TO_AIRP) IS NULL) THEN 1 ELSE 0 END) AS invalid_core_count,
           SUM(CASE WHEN d.ID IS NOT NULL AND TRIM(d.REMARK) IS NOT NULL THEN 1 ELSE 0 END) AS remark_count
      FROM T_PERMMASTER_SC m
      LEFT JOIN T_PERMDETAIL_SC d ON d.PERM_ID = m.PERM_ID
     WHERE m.ID IN ($masterIds)
     GROUP BY m.ID, m.PERM_ID, m.PERMNBR_ID, m.REFERENCE, m.BILLINGADDRESS, m.VERSION,
              CASE WHEN m.PERMCONTENT1 IS NULL THEN 0 ELSE DBMS_LOB.GETLENGTH(m.PERMCONTENT1) END
    UNION ALL
    SELECT 'NO' AS table_kind, m.ID AS master_id, m.PERM_ID AS perm_id,
           m.PERMNBR_ID AS permit_number, m.REFERENCE AS reference_value,
           m.BILLINGADDRESS AS billing_address, m.VERSION AS version_value,
           CASE WHEN m.PERMCONTENT1 IS NULL THEN 0 ELSE DBMS_LOB.GETLENGTH(m.PERMCONTENT1) END AS content_length,
           COUNT(d.ID) AS detail_count,
           SUM(CASE WHEN d.ID IS NOT NULL AND (TRIM(d.FLIGHTNBR) IS NULL OR TRIM(d.FROM_AIRP) IS NULL OR TRIM(d.TO_AIRP) IS NULL) THEN 1 ELSE 0 END) AS invalid_core_count,
           SUM(CASE WHEN d.ID IS NOT NULL AND TRIM(d.REMARK) IS NOT NULL THEN 1 ELSE 0 END) AS remark_count
      FROM T_PERMMASTER_NO m
      LEFT JOIN T_PERMDETAIL_NO d ON d.PERM_ID = m.PERM_ID
     WHERE m.ID IN ($masterIds)
     GROUP BY m.ID, m.PERM_ID, m.PERMNBR_ID, m.REFERENCE, m.BILLINGADDRESS, m.VERSION,
              CASE WHEN m.PERMCONTENT1 IS NULL THEN 0 ELSE DBMS_LOB.GETLENGTH(m.PERMCONTENT1) END
)
SELECT $targetFields FROM target_rows ORDER BY master_id, table_kind;
"@
    Invoke-OracleExport $targetConnect $targetSql $targetPath
    $targetRows = @(Read-DelimitedRows $targetPath $targetColumns)
    $targetByIdentity = $targetRows | Group-Object { "$($_.MasterId):$($_.PermId)" } -AsHashTable -AsString

    $results = foreach ($tracking in $trackingRows) {
        $errors = [Collections.Generic.List[string]]::new()
        $warnings = [Collections.Generic.List[string]]::new()
        $targetIdentity = "$($tracking.TargetMasterId):$($tracking.TargetPermId)"
        $targets = @($targetByIdentity[$targetIdentity])
        $target = if ($targets.Count -eq 1) { $targets[0] } else { $null }

        if ($targets.Count -eq 0) { $errors.Add("target master missing") }
        if ($targets.Count -gt 1) { $errors.Add("target identity exists in both SC and NO") }
        if (-not $tracking.StoredPath -or -not (Test-Path -LiteralPath $tracking.StoredPath)) {
            $errors.Add("source file missing")
        }

        $sourceText = ""
        if ($tracking.StoredPath -and (Test-Path -LiteralPath $tracking.StoredPath)) {
            if ([IO.Path]::GetExtension($tracking.StoredPath).Equals('.docx', [StringComparison]::OrdinalIgnoreCase)) {
                try { $sourceText = Read-DocxText $tracking.StoredPath } catch { $errors.Add("source DOCX unreadable") }
            } else {
                $warnings.Add("legacy DOC was not text-inspected")
            }
        }
        $fileSignal = "$($tracking.OriginalFileName) $($tracking.AttachmentName) $($tracking.Subject)"
        $isSeasonal = $tracking.PermitId -match '/[SW]/CHK/'
        $isRevision = $tracking.PermitId -match '(?i)[A-Z]?R\d+/' `
                -or $fileSignal -match '(?i)\b(?:REVISE|REVISION|REV|RVS)\s*\d*' `
                -or $sourceText -match '(?i)L[IỊ]CH\s+BAY\s+(?:G[ỐO]C|C[ŨU])|OLD\s+SCHEDULE'

        if ($target) {
            if ($target.PermId -ne $tracking.TargetPermId) { $errors.Add("target PERM_ID mismatch") }
            if ($target.PermitNumber -ne $tracking.PermitId) { $errors.Add("normalized permit ID mismatch") }
            $expectedKind = if ($isSeasonal) { "SC" } else { "NO" }
            if ($target.TableKind -ne $expectedKind) { $errors.Add("expected $expectedKind table, found $($target.TableKind)") }
            $detailCountMatches = [int]$target.DetailCount -eq [int]$tracking.ExpectedDetailCount
            $accumulatedCountMatches = [int]$tracking.TargetWriteCount -gt 1 `
                    -and [int]$target.DetailCount -ge [int]$tracking.ExpectedDetailCount
            if (-not $detailCountMatches -and -not $accumulatedCountMatches) { $errors.Add("detail count mismatch") }
            if (-not $detailCountMatches -and $accumulatedCountMatches) {
                $warnings.Add("shared target has accumulated details")
            }
            if ([int]$target.DetailCount -lt 1) { $errors.Add("no target details") }
            if ([int]$target.InvalidCoreCount -gt 0) { $errors.Add("detail missing flight/from/to") }
            if (-not $target.Reference -and $isRevision) { $errors.Add("revision reference is blank") }
            if (-not $target.Reference -and -not $isRevision) { $warnings.Add("original reference is blank") }
            if (-not $target.BillingAddress) { $errors.Add("billing address is blank") }
            if ([int]$target.PermitContentLength -lt 20) { $errors.Add("stored permit content is blank or too short") }
            if ([int]$target.RemarkCount -eq 0) { $warnings.Add("all detail remarks are blank") }
            if ($isRevision -and $target.Reference -notmatch '(?i)RVS|\bR\d+\b') {
                $warnings.Add("revision signal found but reference has no RVS/Rn marker")
            }
        }

        [pscustomobject]@{
            ImportId = $tracking.ImportId
            SyncJobId = $tracking.SyncJobId
            ImportedAt = $tracking.ImportedAt
            File = if ($tracking.OriginalFileName) { $tracking.OriginalFileName } else { $tracking.AttachmentName }
            PermitId = $tracking.PermitId
            Target = if ($target) { "$($target.TableKind):$($target.MasterId)/$($target.PermId)" } else { "$($tracking.TargetMasterId)/$($tracking.TargetPermId)" }
            ExpectedDetails = $tracking.ExpectedDetailCount
            ActualDetails = if ($target) { $target.DetailCount } else { "" }
            Revision = $isRevision
            Errors = $errors -join "; "
            Warnings = $warnings -join "; "
            Result = if ($errors.Count -gt 0) { "FAIL" } elseif ($warnings.Count -gt 0) { "REVIEW" } else { "PASS" }
        }
    }

    $outputDirectory = Split-Path -Parent $OutputPath
    if (-not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    $passCount = @($results | Where-Object Result -eq "PASS").Count
    $reviewCount = @($results | Where-Object Result -eq "REVIEW").Count
    $failCount = @($results | Where-Object Result -eq "FAIL").Count
    $builder = [Text.StringBuilder]::new()
    $null = $builder.AppendLine("# Latest $Limit SAVED permit audit")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("- Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
    $null = $builder.AppendLine("- Scope: latest $Limit tracking imports with status SAVED")
    $null = $builder.AppendLine("- Result: PASS $passCount, REVIEW $reviewCount, FAIL $failCount")
    $null = $builder.AppendLine("- Database access: read-only SELECT statements")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Checks")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("The audit checks target existence and identity, SC/NO routing, detail counts, required schedule fields, reference, billing address, stored source content, remarks, source-file readability, and revision reference markers.")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Findings")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("| Result | Import | Job | Saved at | File | Permit | Target | Details | Revision | Finding |")
    $null = $builder.AppendLine("|---|---:|---:|---|---|---|---|---:|---|---|")
    foreach ($item in $results) {
        $finding = if ($item.Errors) { $item.Errors } else { $item.Warnings }
        $details = "$($item.ExpectedDetails)/$($item.ActualDetails)"
        $null = $builder.AppendLine("| $($item.Result) | $($item.ImportId) | $($item.SyncJobId) | $(Markdown-Cell $item.ImportedAt) | $(Markdown-Cell $item.File) | $(Markdown-Cell $item.PermitId) | $(Markdown-Cell $item.Target) | $details | $($item.Revision) | $(Markdown-Cell $finding) |")
    }
    [IO.File]::WriteAllText($OutputPath, $builder.ToString(), [Text.UTF8Encoding]::new($false))
    $manifestFiles = $trackingRows.StoredPath | Where-Object { $_ } | Select-Object -Unique
    [IO.File]::WriteAllLines($ManifestPath, $manifestFiles, [Text.UTF8Encoding]::new($false))
    Write-Output "Report: $OutputPath"
    Write-Output "Manifest: $ManifestPath"
    Write-Output "PASS=$passCount REVIEW=$reviewCount FAIL=$failCount"
    if ($failCount -gt 0) { exit 2 }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}
