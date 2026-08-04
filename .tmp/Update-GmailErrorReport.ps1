param(
    [string]$ApiBase = 'http://localhost:8081',
    [string]$Sender = 'tu1412000@gmail.com',
    [string]$ReportPath = 'DANH_SACH_HANG_BAY_DA_CO_FORMAT.docx',
    [string]$ManifestPath = 'NGAY 31JUL_RENUMBERED_UNUSED_20260803\RENUMBER_MANIFEST.csv',
    [string]$AirlineMapPath = '.tmp\permit-format-airlines.csv',
    [string]$AtfmOperatorMapPath = '.tmp\atfm-latest-permit-operators.csv'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Repair-Mojibake([AllowNull()][string]$Value) {
    if ([string]::IsNullOrEmpty($Value) -or $Value -notmatch '[ÃÄÆáºá»]') {
        return $Value
    }
    $latin1 = [Text.Encoding]::GetEncoding(28591)
    return [Text.Encoding]::UTF8.GetString($latin1.GetBytes($Value))
}

function Escape-Xml([AllowNull()][string]$Value) {
    if ($null -eq $Value) { return '' }
    $clean = $Value -replace '[\x00-\x08\x0B\x0C\x0E-\x1F]', ' '
    return [Security.SecurityElement]::Escape($clean)
}

function New-Run([string]$Text, [bool]$Bold = $false, [int]$Size = 18, [string]$Color = '000000') {
    $boldXml = if ($Bold) { '<w:b/>' } else { '' }
    return '<w:r><w:rPr>' + $boldXml + '<w:color w:val="' + $Color + '"/><w:sz w:val="' + $Size + '"/><w:szCs w:val="' + $Size + '"/></w:rPr><w:t xml:space="preserve">' + (Escape-Xml $Text) + '</w:t></w:r>'
}

function New-Paragraph([string]$Text, [bool]$Bold = $false, [int]$Size = 20, [string]$Color = '000000') {
    return '<w:p><w:pPr><w:spacing w:after="80"/></w:pPr>' + (New-Run $Text $Bold $Size $Color) + '</w:p>'
}

function New-Cell([string]$Text, [int]$Width, [bool]$Header = $false, [string]$Fill = '') {
    $shade = if ($Fill) { '<w:shd w:val="clear" w:color="auto" w:fill="' + $Fill + '"/>' } else { '' }
    $paragraph = '<w:p><w:pPr><w:spacing w:before="20" w:after="20"/></w:pPr>' + (New-Run $Text $Header 16) + '</w:p>'
    return '<w:tc><w:tcPr><w:tcW w:w="' + $Width + '" w:type="dxa"/>' + $shade + '<w:vAlign w:val="center"/></w:tcPr>' + $paragraph + '</w:tc>'
}

$encodedSender = [Uri]::EscapeDataString($Sender)
$first = Invoke-RestMethod -Uri "$ApiBase/api/reports/emails?sender=$encodedSender&page=0&size=100" -TimeoutSec 30
if (-not $first.content -or $first.content.Count -eq 0) {
    throw "Không tìm thấy bản ghi email của $Sender."
}

$latestMessageId = ($first.content | Sort-Object id -Descending | Select-Object -First 1).messageId
$expected = [int](($first.content | Where-Object messageId -eq $latestMessageId | Select-Object -First 1).attachmentCount)
$all = @($first.content)
$page = 1
while (($all | Where-Object messageId -eq $latestMessageId).Count -lt $expected -and $page -lt $first.totalPages) {
    $response = Invoke-RestMethod -Uri "$ApiBase/api/reports/emails?sender=$encodedSender&page=$page&size=100" -TimeoutSec 30
    $all += @($response.content)
    $page++
}

$latestRows = @($all | Where-Object messageId -eq $latestMessageId | Sort-Object attachmentIndex)
if ($latestRows.Count -ne $expected) {
    throw "Chỉ lấy được $($latestRows.Count)/$expected tệp của email mới nhất."
}

$errorRows = @($latestRows | Where-Object { $_.jobStatus -in @('FAILED', 'QUARANTINED') })
$successCount = @($latestRows | Where-Object jobStatus -eq 'SUCCESS').Count
$failedCount = @($errorRows | Where-Object jobStatus -eq 'FAILED').Count
$quarantinedCount = @($errorRows | Where-Object jobStatus -eq 'QUARANTINED').Count
$ignoredCount = $latestRows.Count - $successCount - $failedCount - $quarantinedCount

$manifestByFile = @{}
Import-Csv -LiteralPath $ManifestPath | ForEach-Object { $manifestByFile[$_.file] = $_ }

$airlineByFormat = @{}
Import-Csv -LiteralPath $AirlineMapPath | ForEach-Object {
    $airline = $_
    @($airline.Formats -split "`r?`n") | Where-Object { $_ } | ForEach-Object {
        $airlineByFormat[$_] = $airline
    }
}

$atfmByPermit = @{}
Import-Csv -LiteralPath $AtfmOperatorMapPath | ForEach-Object {
    $atfmByPermit[$_.PermitNumber] = $_
}

$orderedRows = @($latestRows | Sort-Object `
    @{ Expression = {
        if ($_.jobStatus -eq 'SUCCESS') { 1 }
        elseif ($_.jobStatus -eq 'QUARANTINED') { 2 }
        elseif ($_.jobStatus -eq 'FAILED') { 3 }
        else { 4 }
    } },
    @{ Expression = { [int]$_.attachmentIndex } })

$reportRows = foreach ($row in $orderedRows) {
    $manifest = $manifestByFile[$row.attachmentName]
    $profile = if ($manifest) { [string]$manifest.profile } else { '' }
    $airlineLabel = ''
    $atfmOperator = if ($row.permitNumber) { $atfmByPermit[[string]$row.permitNumber] } else { $null }
    if ($row.jobStatus -eq 'SUCCESS' -and $atfmOperator) {
        $airlineLabel = ($atfmOperator.Icao + ' - ' + $atfmOperator.OperatorName).Trim(' ', '-')
    } elseif ($profile -and $airlineByFormat.ContainsKey($profile)) {
        $airline = $airlineByFormat[$profile]
        $codes = @($airline.Iata, $airline.Icao) | Where-Object { $_ } | Select-Object -Unique
        $airlineLabel = (($codes -join '/') + ' - ' + $airline.Name).Trim(' ', '-')
    } elseif ($profile -match '^([a-z0-9]{3})-') {
        $code = $Matches[1].ToUpperInvariant()
        if ($code -eq 'PRV') {
            $airlineLabel = 'PRV - Hãng tư nhân theo nội dung Word'
        } elseif ($code -eq 'CAAV') {
            $airlineLabel = 'Chưa xác định - format CAAV dùng chung'
        } else {
            $airlineLabel = "$code - nhận diện từ format $profile"
        }
    } elseif ($row.attachmentName -eq 'RENUNBER_MANIFEST.csv' -or $row.attachmentName -eq 'RENUMBER_MANIFEST.csv') {
        $airlineLabel = 'Không áp dụng - tệp manifest CSV'
    } else {
        $airlineLabel = 'Chưa xác định hãng/không phải giấy phép'
    }

    $fixedError = Repair-Mojibake ([string]$row.errorMessage)
    if ($fixedError -match 'M_OPER\.OPER_ICAO=([A-Z0-9]{3})') {
        $icaoFromError = $Matches[1]
        if ($airlineLabel -like 'Chưa xác định*') {
            $airlineLabel = "$icaoFromError - mã ICAO trích từ lỗi đối chiếu ATFM"
        }
    }

    [pscustomobject]@{
        ReceivedAt = ([datetime]$row.receivedAt).ToString('dd/MM/yyyy HH:mm:ss')
        AttachmentIndex = [int]$row.attachmentIndex
        FileName = [string]$row.attachmentName
        Airline = $airlineLabel
        Profile = if ($profile) { $profile } else { '(không có)' }
        PermitNumber = if ($row.permitNumber) { [string]$row.permitNumber } else { '(chưa lấy được)' }
        Status = if ($row.jobStatus -eq 'SUCCESS') { 'SAVED - THÀNH CÔNG' }
            elseif ($row.jobStatus -eq 'QUARANTINED') { 'QUARANTINED - CÁCH LY' }
            elseif ($row.jobStatus -eq 'FAILED') { 'FAILED - THẤT BẠI' }
            else { ([string]$row.processingStatus + ' - KHÔNG THUỘC LUỒNG XỬ LÝ').Trim(' ', '-') }
        Error = if ($row.jobStatus -eq 'SUCCESS') { 'Không có lỗi - đã insert vào ATFM.' }
            elseif ($fixedError) { $fixedError }
            else { '(không có thông báo lỗi)' }
    }
}

$csvPath = '.tmp\gmail-tu1412000-latest-processing.csv'
$reportRows | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

$subject = Repair-Mojibake ([string]$latestRows[0].subject)
$receivedAt = ([datetime]$latestRows[0].receivedAt).ToString('dd/MM/yyyy HH:mm:ss')
$airlineCount = @($reportRows | Where-Object { $_.Airline -notlike 'Không áp dụng*' -and $_.Airline -notlike 'Chưa xác định hãng/không phải*' } | Select-Object -ExpandProperty Airline -Unique).Count

$table = '<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblLayout w:type="fixed"/><w:tblBorders>' +
    '<w:top w:val="single" w:sz="4" w:color="808080"/><w:left w:val="single" w:sz="4" w:color="808080"/>' +
    '<w:bottom w:val="single" w:sz="4" w:color="808080"/><w:right w:val="single" w:sz="4" w:color="808080"/>' +
    '<w:insideH w:val="single" w:sz="4" w:color="BFBFBF"/><w:insideV w:val="single" w:sz="4" w:color="BFBFBF"/>' +
    '</w:tblBorders></w:tblPr><w:tblGrid>' +
    '<w:gridCol w:w="500"/><w:gridCol w:w="1500"/><w:gridCol w:w="3200"/><w:gridCol w:w="2500"/>' +
    '<w:gridCol w:w="1800"/><w:gridCol w:w="1100"/><w:gridCol w:w="4800"/></w:tblGrid>'

$table += '<w:tr><w:trPr><w:tblHeader/></w:trPr>' +
    (New-Cell 'STT' 500 $true 'D9EAF7') +
    (New-Cell 'Thời gian nhận' 1500 $true 'D9EAF7') +
    (New-Cell 'Tên file' 3200 $true 'D9EAF7') +
    (New-Cell 'Hãng / mã nhận diện' 2500 $true 'D9EAF7') +
    (New-Cell 'Số phép bay' 1800 $true 'D9EAF7') +
    (New-Cell 'Trạng thái' 1100 $true 'D9EAF7') +
    (New-Cell 'Kết quả / thông báo lỗi' 4800 $true 'D9EAF7') + '</w:tr>'

$index = 1
foreach ($item in $reportRows) {
    $fill = if (($index % 2) -eq 0) { 'F7F7F7' } else { '' }
    $table += '<w:tr>' +
        (New-Cell ([string]$index) 500 $false $fill) +
        (New-Cell $item.ReceivedAt 1500 $false $fill) +
        (New-Cell $item.FileName 3200 $false $fill) +
        (New-Cell ($item.Airline + "`nFormat: " + $item.Profile) 2500 $false $fill) +
        (New-Cell $item.PermitNumber 1800 $false $fill) +
        (New-Cell $item.Status 1100 $false $fill) +
        (New-Cell $item.Error 4800 $false $fill) + '</w:tr>'
    $index++
}
$table += '</w:tbl>'

$section = '<w:p><w:r><w:br w:type="page"/></w:r></w:p>' +
    (New-Paragraph "THỐNG KÊ KẾT QUẢ XỬ LÝ FILE - $Sender" $true 28 '1F4E78') +
    (New-Paragraph "Phạm vi: email mới nhất; tiêu đề: $subject; nhận lúc: $receivedAt" $false 20) +
    (New-Paragraph "Tổng $($latestRows.Count) tệp: $successCount thành công, $failedCount thất bại, $quarantinedCount cách ly, $ignoredCount tệp hướng dẫn/không thuộc luồng xử lý." $true 20) +
    (New-Paragraph "Bảng có đủ $($reportRows.Count) tệp và được sắp xếp: SAVED, QUARANTINED, FAILED, sau đó là tệp không thuộc luồng xử lý. Có $airlineCount nhóm hãng/mã nhận diện. Hãng của bản ghi SAVED được đối chiếu trực tiếp từ ATFM.M_OPER." $false 18) +
    $table + '<w:p/>'

$resolvedReport = (Resolve-Path -LiteralPath $ReportPath).Path
$backupDir = Join-Path (Split-Path $resolvedReport -Parent) '.tmp'
if (-not (Test-Path -LiteralPath $backupDir)) { New-Item -ItemType Directory -Path $backupDir | Out-Null }
$backupPath = Join-Path $backupDir ('DANH_SACH_HANG_BAY_DA_CO_FORMAT_before_gmail_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.docx')
Copy-Item -LiteralPath $resolvedReport -Destination $backupPath

$tempDocx = Join-Path $backupDir ('gmail-report-' + [guid]::NewGuid().ToString('N') + '.docx')
Copy-Item -LiteralPath $resolvedReport -Destination $tempDocx
$stream = [IO.File]::Open($tempDocx, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite)
try {
    $archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Update, $false)
    try {
        $entry = $archive.GetEntry('word/document.xml')
        if (-not $entry) { $entry = $archive.GetEntry('word\document.xml') }
        if (-not $entry) { throw 'DOCX không có word/document.xml.' }
        $documentEntryName = $entry.FullName
        $reader = New-Object IO.StreamReader($entry.Open(), [Text.Encoding]::UTF8)
        try { $xml = $reader.ReadToEnd() } finally { $reader.Dispose() }

        $sectIndex = $xml.LastIndexOf('<w:sectPr')
        if ($sectIndex -lt 0) { throw 'Không tìm thấy w:sectPr trong tài liệu Word.' }
        $oldHeadingIndex = $xml.LastIndexOf('THỐNG KÊ FILE LỖI -')
        if ($oldHeadingIndex -ge 0) {
            $pageBreak = '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'
            $oldSectionStart = $xml.LastIndexOf($pageBreak, $oldHeadingIndex)
            if ($oldSectionStart -ge 0) {
                $xml = $xml.Remove($oldSectionStart, $sectIndex - $oldSectionStart)
                $sectIndex = $xml.LastIndexOf('<w:sectPr')
            }
        }
        $xml = $xml.Insert($sectIndex, $section)
        $xml = $xml -replace '<w:pgSz w:w="11906" w:h="16838"/>', '<w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/>'

        $entry.Delete()
        $newEntry = $archive.CreateEntry($documentEntryName, [IO.Compression.CompressionLevel]::Optimal)
        $writer = New-Object IO.StreamWriter($newEntry.Open(), (New-Object Text.UTF8Encoding($false)))
        try { $writer.Write($xml) } finally { $writer.Dispose() }
    } finally {
        $archive.Dispose()
    }
} finally {
    $stream.Dispose()
}

Move-Item -LiteralPath $tempDocx -Destination $resolvedReport -Force

[pscustomobject]@{
    Report = $resolvedReport
    Backup = $backupPath
    CsvEvidence = (Resolve-Path -LiteralPath $csvPath).Path
    LatestMessageId = $latestMessageId
    Attachments = $latestRows.Count
    Success = $successCount
    Failed = $failedCount
    Quarantined = $quarantinedCount
    ReportRows = $reportRows.Count
    AirlineGroups = $airlineCount
} | ConvertTo-Json -Compress
