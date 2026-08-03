$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$csv = Import-Csv -LiteralPath (Join-Path $root '.tmp\permit-format-airlines.csv')
$output = Join-Path $root 'DANH_SACH_HANG_BAY_DA_CO_FORMAT.docx'
$package = Join-Path $root ('.tmp\format-report-package-' + [Guid]::NewGuid().ToString('N'))
$rels = Join-Path $package '_rels'
$word = Join-Path $package 'word'
New-Item -ItemType Directory -Path $rels,$word -Force | Out-Null

function Escape-Xml([string] $value) {
    return [Security.SecurityElement]::Escape(($value -replace '[\x00-\x08\x0B\x0C\x0E-\x1F]', ''))
}

function Paragraph([string] $text, [bool] $bold = $false, [int] $size = 20) {
    $escaped = Escape-Xml $text
    $boldXml = if ($bold) { '<w:b/>' } else { '' }
    return '<w:p><w:pPr><w:spacing w:after="80"/></w:pPr><w:r><w:rPr>' + $boldXml + '<w:sz w:val="' + $size + '"/></w:rPr><w:t xml:space="preserve">' + $escaped + '</w:t></w:r></w:p>'
}

$body = New-Object Text.StringBuilder
[void]$body.Append((Paragraph 'DANH SÁCH HÃNG BAY ĐÃ CÓ FORMAT' $true 32))
[void]$body.Append((Paragraph 'Ngày lập: 03/08/2026' $false 22))
[void]$body.Append((Paragraph ("Tổng số nhóm hãng/đơn vị: {0}. IATA, ICAO và tên hãng được đối chiếu từ ATFM.M_OPER." -f $csv.Count) $false 20))
[void]$body.Append((Paragraph 'Corpus NGAY 31JUL: 186 giấy phép hợp lệ, 0 lỗi; 1 tài liệu không phải giấy phép.' $false 20))
[void]$body.Append((Paragraph '' $false 20))

$index = 0
foreach ($row in $csv) {
    $index++
    [void]$body.Append((Paragraph ("{0}. {1}" -f $index, $row.Name) $true 21))
    [void]$body.Append((Paragraph ("IATA: {0} | ICAO: {1}" -f $row.Iata, $row.Icao) $false 20))
    [void]$body.Append((Paragraph ("Format YAML: {0}" -f (($row.Formats -replace '[\r\n]+','; '))) $false 18))
    [void]$body.Append((Paragraph ("Word mẫu: {0} | Chuyến bay đọc được: {1}" -f $row.Documents, $row.Flights) $false 18))
}
[void]$body.Append((Paragraph 'Ghi chú: Mã PRV dùng cho đơn vị không có ICAO hợp lệ hoặc không xác định được duy nhất. Các format CAAV/QLB chung là template nền nên không liệt kê như một hãng riêng.' $false 18))

$documentXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
    $body.ToString() +
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr>' +
    '</w:body></w:document>'

$contentTypes = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>
'@
$packageRels = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
'@

$utf8 = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $package '[Content_Types].xml'), $contentTypes, $utf8)
[IO.File]::WriteAllText((Join-Path $rels '.rels'), $packageRels, $utf8)
[IO.File]::WriteAllText((Join-Path $word 'document.xml'), $documentXml, $utf8)
if (Test-Path -LiteralPath $output) {
    $backup = $output + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    Move-Item -LiteralPath $output -Destination $backup
}
[IO.Compression.ZipFile]::CreateFromDirectory($package, $output)
"OUTPUT=$output"
"AIRLINE_GROUPS=$($csv.Count)"
