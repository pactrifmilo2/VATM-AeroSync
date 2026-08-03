$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$formatDir = Join-Path $root 'aerosync-worker\src\main\resources\permit-formats'
$corpusLog = Join-Path $root '.tmp\ngay31jul-corpus-pass.log'
$operatorFile = Join-Path $root '.tmp\m-oper-all.tsv'
$output = Join-Path $root 'DANH_SACH_HANG_BAY_DA_CO_FORMAT.docx'

$usage = @{}
if (Test-Path -LiteralPath $corpusLog) {
    foreach ($line in Get-Content -LiteralPath $corpusLog -Encoding UTF8) {
        if ($line -match '^.*? \| (?<profile>[a-z0-9-]+) \| .*? \| (?<flights>\d+) flight') {
            if (-not $usage.ContainsKey($Matches.profile)) {
                $usage[$Matches.profile] = [pscustomobject]@{ Documents = 0; Flights = 0 }
            }
            $usage[$Matches.profile].Documents++
            $usage[$Matches.profile].Flights += [int]$Matches.flights
        }
    }
}

$operatorsByIcao = @{}
foreach ($line in Get-Content -LiteralPath $operatorFile -Encoding UTF8) {
    $parts = $line -split "`t", 4
    if ($parts.Count -lt 4) { continue }
    $iata = $parts[1].Trim().ToUpperInvariant()
    $icao = $parts[2].Trim().ToUpperInvariant()
    $name = $parts[3].Trim()
    if ($icao -notmatch '^[A-Z0-9]{3}$') { continue }
    if (-not $operatorsByIcao.ContainsKey($icao)) { $operatorsByIcao[$icao] = @() }
    $operatorsByIcao[$icao] += [pscustomobject]@{ Iata = $iata; Name = $name }
}

$genericIds = @(
    'caav-english-issued-permit-revision',
    'caav-english-landing-revision',
    'caav-english-overflight-revision',
    'caav-english-overflight-scheduled',
    'caav-generic-landing-issued',
    'caav-generic-landing-revision',
    'caav-generic-overflight-issued',
    'caav-generic-overflight-revision',
    'caav-vietnamese-landing-correction',
    'qlb-generic-issued'
)

$profiles = foreach ($file in Get-ChildItem -LiteralPath $formatDir -File -Filter '*.yaml' | Sort-Object Name) {
    $raw = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    if ($raw -notmatch '(?m)^id:\s*(?<id>[^\r\n#]+)') { continue }
    $id = $Matches.id.Trim().Trim("'").Trim('"')
    $fixed = ''
    if ($raw -match '(?m)^\s*fixedValue:\s*["'']?(?<code>[A-Z0-9]{3})') {
        $fixed = $Matches.code.ToUpperInvariant()
    }
    if (-not $fixed -and $id -notin $genericIds) {
        $prefix = ($id -split '-')[0].ToUpperInvariant()
        if ($prefix -match '^[A-Z0-9]{3}$') { $fixed = $prefix }
    }
    $used = $usage[$id]
    [pscustomobject]@{
        Id = $id
        Icao = $fixed
        Documents = if ($used) { $used.Documents } else { 0 }
        Flights = if ($used) { $used.Flights } else { 0 }
        Generic = ($id -in $genericIds)
    }
}

$carrierProfiles = $profiles | Where-Object { -not $_.Generic }
$groups = $carrierProfiles | Group-Object {
    if ($_.Icao -eq 'PRV' -or -not $_.Icao) { $_.Id } else { $_.Icao }
}

$rows = foreach ($group in $groups) {
    $icao = ($group.Group | Where-Object Icao | Select-Object -First 1).Icao
    $iata = ''
    $name = ''
    if ($icao -and $icao -ne 'PRV' -and $operatorsByIcao.ContainsKey($icao)) {
        $matches = @($operatorsByIcao[$icao])
        $iata = (($matches.Iata | Where-Object { $_ -match '^[A-Z0-9]{2}$' } | Sort-Object -Unique) -join ', ')
        $name = (($matches.Name | Where-Object { $_ } | Sort-Object -Unique | Select-Object -First 3) -join ' / ')
    }
    if (-not $name) {
        $name = (($group.Name -replace '^prv-', '' -replace '-(english|vietnamese|bilingual).*$','') -replace '-', ' ')
        $name = (Get-Culture).TextInfo.ToTitleCase($name)
    }
    [pscustomobject]@{
        Iata = $iata
        Icao = if ($icao) { $icao } else { 'PRV' }
        Name = $name
        Formats = (($group.Group.Id | Sort-Object) -join "`n")
        Documents = ($group.Group | Measure-Object Documents -Sum).Sum
        Flights = ($group.Group | Measure-Object Flights -Sum).Sum
    }
}
$rows = @($rows | Sort-Object Icao, Name)

$exportRows = $rows | Select-Object Iata,Icao,Name,Formats,Documents,Flights
$exportRows | Export-Csv -LiteralPath (Join-Path $root '.tmp\permit-format-airlines.csv') -NoTypeInformation -Encoding UTF8

$word = $null
$document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $document = $word.Documents.Add()
    $document.PageSetup.Orientation = 1
    $document.PageSetup.TopMargin = $word.CentimetersToPoints(1.5)
    $document.PageSetup.BottomMargin = $word.CentimetersToPoints(1.5)
    $document.PageSetup.LeftMargin = $word.CentimetersToPoints(1.5)
    $document.PageSetup.RightMargin = $word.CentimetersToPoints(1.5)

    $range = $document.Range(0, 0)
    $range.Text = "DANH SÁCH HÃNG BAY ĐÃ CÓ FORMAT`r"
    $range.Style = 'Title'
    $range.ParagraphFormat.Alignment = 1
    $range.Collapse(0)
    $range.Text = "Ngày lập: 03/08/2026`r"
    $range.Style = 'Subtitle'
    $range.ParagraphFormat.Alignment = 1
    $range.Collapse(0)
    $range.Text = "Tổng số YAML đang hoạt động: $($profiles.Count). Số nhóm hãng/đơn vị có format riêng: $($rows.Count). Corpus NGAY 31JUL: 186 giấy phép hợp lệ, 0 lỗi; 1 tài liệu không phải giấy phép.`r`r"
    $range.Style = 'Normal'
    $range.ParagraphFormat.Alignment = 0

    $reportLines = New-Object System.Collections.Generic.List[string]
    for ($index = 0; $index -lt $rows.Count; $index++) {
        $row = $rows[$index]
        $formats = $row.Formats -replace '[\t\r\n]+', '; '
        $reportLines.Add(("{0}. {1}`r   IATA: {2} | ICAO: {3}`r   Format: {4}`r   Word mẫu: {5} | Chuyến bay đọc được: {6}`r" -f `
                    ($index + 1), $row.Name, $row.Iata, $row.Icao, $formats, $row.Documents, $row.Flights))
    }
    $end = $document.Range($document.Content.End - 1, $document.Content.End - 1)
    $end.Text = ($reportLines -join "`r") + "`rGhi chú: IATA/ICAO và tên hãng được đối chiếu từ ATFM.M_OPER. Mã PRV dùng cho đơn vị không có ICAO hợp lệ hoặc không xác định được duy nhất. Các format CAAV/QLB chung là template nền nên không liệt kê như một hãng riêng."
    $end.Style = 'Normal'

    $document.SaveAs2($output, 12)
    "OUTPUT=$output"
    "AIRLINE_GROUPS=$($rows.Count)"
    "PROFILES=$($profiles.Count)"
}
finally {
    if ($document) { $document.Close(0); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($document) }
    if ($word) { $word.Quit(); [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
