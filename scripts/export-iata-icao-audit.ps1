param(
    [string] $OutputPath
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
if (-not (Test-Path $envPath)) {
    throw "Không tìm thấy .env"
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $root ("reports\bao-cao-iata-icao-{0}.md" -f (Get-Date -Format "yyyy-MM-dd"))
}

$envValues = @{}
foreach ($line in Get-Content $envPath) {
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

function Sql-Field {
    param([string] $Expression)
    return "REPLACE(REPLACE(REPLACE(NVL($Expression, ''), CHR(13), ' '), CHR(10), ' '), '~^~', ' ')"
}

function Read-DocxText {
    param([string] $Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry("word/document.xml")
        if (-not $entry) { throw "Thiếu word/document.xml" }
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

function Match-Code {
    param([string] $Text, [string] $Kind, [int] $Length)
    $match = [regex]::Match(
        $Text,
        "(?i)(?:$Kind\s*(?:CODE)?|M[ÃA]\s*$Kind)(?:\s*\([^)]*\))?\s*:\s*(?<value>[A-Z0-9]{$Length})(?![A-Z0-9])")
    if ($match.Success) { return $match.Groups['value'].Value.ToUpperInvariant() }
    return ""
}

function Has-Label {
    param([string] $Text, [string] $Kind)
    return [regex]::IsMatch($Text, "(?i)(?:$Kind\s*(?:CODE)?|M[ÃA]\s*$Kind)(?:\s*\([^)]*\))?\s*:")
}

function Is-ExplicitNa {
    param([string] $Text, [string] $Kind)
    return [regex]::IsMatch(
        $Text,
        "(?i)(?:$Kind\s*(?:CODE)?|M[ÃA]\s*$Kind)(?:\s*\([^)]*\))?\s*:\s*(?:N\s*/?\s*A|NONE|KHÔNG\s+CÓ)\b")
}

function Carrier-Name {
    param([string] $Text)
    $operator = [regex]::Match(
        $Text,
        '(?is)(?:1\.?\s*(?:OPERATOR|NGƯỜI\s+KHAI\s+THÁC).*?)?\bNAME\s*:\s*(?<value>[^\r\n|]{2,120})')
    if ($operator.Success) { return $operator.Groups['value'].Value.Trim() }
    return ""
}

function ConvertTo-MarkdownCell {
    param($Value)
    if ($null -eq $Value) { return "" }
    return ($Value.ToString() -replace '\|', '\|' -replace '[\r\n]+', ' ').Trim()
}

$url = Resolve-EnvValue "SPRING_DATASOURCE_URL"
if ($url -notmatch '@//([^:/]+):(\d+)/(.+)$') {
    throw "SPRING_DATASOURCE_URL không đúng định dạng Oracle JDBC"
}
$dbHost = $Matches[1]
$dbPort = $Matches[2]
$dbService = $Matches[3]
$dbUser = Resolve-EnvValue "SPRING_DATASOURCE_USERNAME"
$dbPassword = Resolve-EnvValue "SPRING_DATASOURCE_PASSWORD"
$connect = "$dbUser/`"$($dbPassword.Replace('"', '""'))`"@$dbHost`:$dbPort/$dbService"

$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("aerosync-iata-audit-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tempDir | Out-Null
$exportPath = Join-Path $tempDir "metadata.txt"
try {
    $fields = @(
        (Sql-Field "TO_CHAR(em.ID)"),
        (Sql-Field "TO_CHAR(em.SYNC_JOB_ID)"),
        (Sql-Field "em.ATTACHMENT_NAME"),
        (Sql-Field "em.SENDER"),
        (Sql-Field "em.SUBJECT"),
        (Sql-Field "TO_CHAR(em.RECEIVED_AT, 'YYYY-MM-DD HH24:MI:SS')"),
        (Sql-Field "em.PROCESSING_STATUS"),
        (Sql-Field "sj.STATUS"),
        (Sql-Field "lf.STORED_PATH"),
        (Sql-Field "pi.STATUS"),
        (Sql-Field "pi.NORMALIZED_PERMIT_ID"),
        (Sql-Field "TO_CHAR(pi.TARGET_MASTER_ID)"),
        (Sql-Field "TO_CHAR(pi.TARGET_PERM_ID)")
    ) -join " || '~^~' || "
    $sql = @"
WHENEVER SQLERROR EXIT SQL.SQLCODE
CONNECT $connect
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF VERIFY OFF ECHO OFF LINESIZE 32767 TRIMSPOOL ON
SPOOL "$exportPath"
WITH latest_file AS (
    SELECT fr.*, ROW_NUMBER() OVER (PARTITION BY fr.SYNC_JOB_ID ORDER BY fr.ID DESC) AS rn
      FROM FILE_RECORDS fr
)
SELECT $fields
  FROM EMAIL_METADATA em
  LEFT JOIN SYNC_JOBS sj ON sj.ID = em.SYNC_JOB_ID
  LEFT JOIN latest_file lf ON lf.SYNC_JOB_ID = em.SYNC_JOB_ID AND lf.rn = 1
  LEFT JOIN PERMIT_IMPORTS pi ON pi.SYNC_JOB_ID = em.SYNC_JOB_ID
 WHERE LOWER(em.ATTACHMENT_NAME) LIKE '%.doc'
    OR LOWER(em.ATTACHMENT_NAME) LIKE '%.docx'
 ORDER BY em.ID DESC;
SPOOL OFF
EXIT
"@
    $sql | & sqlplus.exe -L -S /nolog | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "SQLPlus export thất bại" }

    $records = foreach ($line in Get-Content $exportPath) {
        if (-not $line.Contains('~^~')) { continue }
        $parts = $line -split '\~\^\~', 13
        [pscustomobject]@{
            EmailId = $parts[0].Trim(); SyncJobId = $parts[1].Trim()
            Attachment = $parts[2].Trim(); Sender = $parts[3].Trim()
            Subject = $parts[4].Trim(); ReceivedAt = $parts[5].Trim()
            ProcessingStatus = $parts[6].Trim(); JobStatus = $parts[7].Trim()
            StoredPath = $parts[8].Trim(); PermitStatus = $parts[9].Trim()
            PermitId = $parts[10].Trim(); TargetMasterId = $parts[11].Trim()
            TargetPermId = $parts[12].Trim()
        }
    }

    $audited = foreach ($group in ($records | Group-Object { if ($_.SyncJobId) { "J:$($_.SyncJobId)" } else { "E:$($_.EmailId)" } })) {
        $row = $group.Group | Select-Object -First 1
        $text = ""
        $readStatus = "Đã đọc"
        $fileExists = $false
        if ($row.StoredPath) {
            try { $fileExists = Test-Path -LiteralPath $row.StoredPath -ErrorAction Stop } catch { $fileExists = $false }
        }
        if (-not $fileExists) {
            $readStatus = "Không còn file vật lý"
        } elseif ([IO.Path]::GetExtension($row.StoredPath).Equals('.docx', [StringComparison]::OrdinalIgnoreCase)) {
            try { $text = Read-DocxText $row.StoredPath } catch { $readStatus = "DOCX lỗi kỹ thuật" }
        } else {
            $readStatus = "DOC cũ - chưa trích xuất tự động"
        }

        $iata = if ($text) { Match-Code $text "IATA" 2 } else { "" }
        $icao = if ($text) { Match-Code $text "ICAO" 3 } else { "" }
        $hasIata = $text -and (Has-Label $text "IATA")
        $hasIcao = $text -and (Has-Label $text "ICAO")
        $iataNa = $text -and (Is-ExplicitNa $text "IATA")
        $icaoNa = $text -and (Is-ExplicitNa $text "ICAO")
        $category = if ($readStatus -ne "Đã đọc") {
            $readStatus
        } elseif ($iata -and $icao) {
            "Đầy đủ IATA + ICAO"
        } elseif ($iata -and -not $icao) {
            "Thiếu ICAO"
        } elseif ($icao -and -not $iata) {
            "Thiếu IATA"
        } elseif ($iataNa -and $icaoNa) {
            "IATA + ICAO ghi N/A"
        } elseif ($hasIata -and $hasIcao) {
            "Trống cả IATA + ICAO"
        } elseif ($hasIata -or $hasIcao) {
            "Chỉ có một nhãn, không có mã hợp lệ"
        } else {
            "Không có nhãn IATA/ICAO"
        }
        $savedWithoutCodes = $readStatus -eq "Đã đọc" `
                -and (-not $iata -or -not $icao) `
                -and $row.PermitStatus -eq "SAVED"
        [pscustomobject]@{
            EmailIds = (($group.Group.EmailId | Sort-Object {[long]$_} -Descending) -join ', ')
            SyncJobId = $row.SyncJobId
            Attachment = $row.Attachment
            Carrier = if ($text) { Carrier-Name $text } else { "" }
            Iata = $iata; Icao = $icao; Category = $category
            ProcessingStatus = (($group.Group.ProcessingStatus | Sort-Object -Unique) -join ', ')
            PermitStatus = $row.PermitStatus; PermitId = $row.PermitId
            Target = if ($row.TargetMasterId) { "$($row.TargetMasterId)/$($row.TargetPermId)" } else { "" }
            SavedWithoutCodes = $savedWithoutCodes
            StoredPath = $row.StoredPath
        }
    }

    $outputDir = Split-Path -Parent $OutputPath
    if (-not (Test-Path $outputDir)) { New-Item -ItemType Directory -Path $outputDir -Force | Out-Null }
    $builder = [Text.StringBuilder]::new()
    $null = $builder.AppendLine("# Báo cáo kiểm tra IATA/ICAO của file Word")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("- Thời điểm tạo: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
    $null = $builder.AppendLine("- Tổng email Word: $($records.Count)")
    $null = $builder.AppendLine("- Tổng file/SyncJob duy nhất: $($audited.Count)")
    $null = $builder.AppendLine("- File đã SAVED khi thiếu ít nhất một mã: $(($audited | Where-Object SavedWithoutCodes).Count)")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Quy tắc cần áp dụng")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("| Trường hợp trong Word | Kết quả đúng | Có dùng CAAV fallback? |")
    $null = $builder.AppendLine("|---|---|---|")
    $null = $builder.AppendLine("| Có IATA và ICAO hợp lệ | Chỉ chọn YAML riêng của hãng; không có YAML thì FAILED | Không |")
    $null = $builder.AppendLine("| Có IATA nhưng thiếu/trống ICAO | FAILED: thiếu ICAO | Không |")
    $null = $builder.AppendLine("| Có ICAO nhưng thiếu/trống IATA | FAILED: thiếu IATA | Không |")
    $null = $builder.AppendLine("| Có cả hai nhãn nhưng cả hai trống | FAILED: IATA và ICAO trống | Không |")
    $null = $builder.AppendLine("| Cả hai ghi rõ N/A | Xử lý theo profile private/PRV được phê duyệt | Chỉ profile N/A riêng |")
    $null = $builder.AppendLine("| Nội dung hoàn toàn không có nhãn IATA/ICAO | Thử CAAV fallback; không khớp thì FAILED YAML | Có |")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Luồng hoạt động")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine('```mermaid')
    $null = $builder.AppendLine('flowchart TD')
    $null = $builder.AppendLine('    A[Đọc file Word] --> B{Đọc được nội dung?}')
    $null = $builder.AppendLine('    B -- Không --> F1[FAILED: lỗi kỹ thuật đọc Word]')
    $null = $builder.AppendLine('    B -- Có --> C{Có nhãn IATA/ICAO?}')
    $null = $builder.AppendLine('    C -- Không có cả hai --> D[Thử format CAAV fallback]')
    $null = $builder.AppendLine('    D --> D1{Có YAML phù hợp?}')
    $null = $builder.AppendLine('    D1 -- Không --> F2[FAILED: file chưa có định dạng YAML]')
    $null = $builder.AppendLine('    D1 -- Có --> V[Parse và validate dữ liệu]')
    $null = $builder.AppendLine('    C -- Có nhãn --> E{IATA và ICAO đều hợp lệ?}')
    $null = $builder.AppendLine('    E -- Thiếu hoặc trống --> F3[FAILED: thông báo rõ mã bị thiếu]')
    $null = $builder.AppendLine('    E -- Đầy đủ --> G[Chọn YAML riêng theo IATA/ICAO]')
    $null = $builder.AppendLine('    G --> G1{Có YAML hãng phù hợp?}')
    $null = $builder.AppendLine('    G1 -- Không --> F2')
    $null = $builder.AppendLine('    G1 -- Có --> V')
    $null = $builder.AppendLine('    V --> H{Dữ liệu bắt buộc hợp lệ?}')
    $null = $builder.AppendLine('    H -- Không --> F4[FAILED/QUARANTINED kèm lỗi tiếng Việt]')
    $null = $builder.AppendLine('    H -- Có --> R[Đối chiếu sân bay, hãng, tàu bay, đường bay]')
    $null = $builder.AppendLine('    R --> R1{Tìm thấy đường bay?}')
    $null = $builder.AppendLine('    R1 -- Không --> I[Insert bình thường, VIA để trống]')
    $null = $builder.AppendLine('    R1 -- Có --> I2[Insert với VIA tìm được]')
    $null = $builder.AppendLine('```')
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Tổng hợp theo trường hợp")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("| Trường hợp | Số file duy nhất | Đã SAVED |")
    $null = $builder.AppendLine("|---|---:|---:|")
    foreach ($categoryGroup in ($audited | Group-Object Category | Sort-Object Count -Descending)) {
        $saved = @($categoryGroup.Group | Where-Object PermitStatus -eq 'SAVED').Count
        $null = $builder.AppendLine("| $(ConvertTo-MarkdownCell $categoryGroup.Name) | $($categoryGroup.Count) | $saved |")
    }
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Tổng hợp theo hãng đọc được từ file Word")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("| Hãng | Tổng file | Đủ IATA + ICAO | Thiếu IATA | Thiếu ICAO | Cả hai trống | Không có nhãn | N/A | Đã SAVED khi thiếu mã |")
    $null = $builder.AppendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($carrierGroup in ($audited | Where-Object Carrier | Group-Object Carrier | Sort-Object Name)) {
        $full = @($carrierGroup.Group | Where-Object Category -eq 'Đầy đủ IATA + ICAO').Count
        $missingIata = @($carrierGroup.Group | Where-Object Category -eq 'Thiếu IATA').Count
        $missingIcao = @($carrierGroup.Group | Where-Object Category -eq 'Thiếu ICAO').Count
        $bothBlank = @($carrierGroup.Group | Where-Object Category -eq 'Trống cả IATA + ICAO').Count
        $noLabels = @($carrierGroup.Group | Where-Object Category -eq 'Không có nhãn IATA/ICAO').Count
        $notApplicable = @($carrierGroup.Group | Where-Object Category -eq 'IATA + ICAO ghi N/A').Count
        $savedIncomplete = @($carrierGroup.Group | Where-Object SavedWithoutCodes).Count
        $null = $builder.AppendLine("| $(ConvertTo-MarkdownCell $carrierGroup.Name) | $($carrierGroup.Count) | $full | $missingIata | $missingIcao | $bothBlank | $noLabels | $notApplicable | $savedIncomplete |")
    }
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Danh sách cần ưu tiên kiểm tra: đã insert khi IATA/ICAO không đầy đủ")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("| Email ID | SyncJob | File | Hãng đọc từ Word | IATA | ICAO | Trường hợp | Permit | Target master/perm |")
    $null = $builder.AppendLine("|---|---:|---|---|---|---|---|---|---|")
    foreach ($item in ($audited | Where-Object SavedWithoutCodes | Sort-Object {[long]$_.SyncJobId} -Descending)) {
        $null = $builder.AppendLine("| $(ConvertTo-MarkdownCell $item.EmailIds) | $(ConvertTo-MarkdownCell $item.SyncJobId) | $(ConvertTo-MarkdownCell $item.Attachment) | $(ConvertTo-MarkdownCell $item.Carrier) | $(ConvertTo-MarkdownCell $item.Iata) | $(ConvertTo-MarkdownCell $item.Icao) | $(ConvertTo-MarkdownCell $item.Category) | $(ConvertTo-MarkdownCell $item.PermitId) | $(ConvertTo-MarkdownCell $item.Target) |")
    }
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("## Danh sách đầy đủ tất cả file Word")
    $null = $builder.AppendLine()
    $null = $builder.AppendLine("| Email ID | SyncJob | File | Hãng đọc từ Word | IATA | ICAO | Trường hợp | Processing | Permit status | Permit | Đường dẫn |")
    $null = $builder.AppendLine("|---|---:|---|---|---|---|---|---|---|---|---|")
    foreach ($item in ($audited | Sort-Object {[long]$_.SyncJobId} -Descending)) {
        $null = $builder.AppendLine("| $(ConvertTo-MarkdownCell $item.EmailIds) | $(ConvertTo-MarkdownCell $item.SyncJobId) | $(ConvertTo-MarkdownCell $item.Attachment) | $(ConvertTo-MarkdownCell $item.Carrier) | $(ConvertTo-MarkdownCell $item.Iata) | $(ConvertTo-MarkdownCell $item.Icao) | $(ConvertTo-MarkdownCell $item.Category) | $(ConvertTo-MarkdownCell $item.ProcessingStatus) | $(ConvertTo-MarkdownCell $item.PermitStatus) | $(ConvertTo-MarkdownCell $item.PermitId) | $(ConvertTo-MarkdownCell $item.StoredPath) |")
    }
    [IO.File]::WriteAllText($OutputPath, $builder.ToString(), [Text.UTF8Encoding]::new($false))
    Write-Output $OutputPath
} finally {
    if (Test-Path $tempDir) { Remove-Item -LiteralPath $tempDir -Recurse -Force }
}
