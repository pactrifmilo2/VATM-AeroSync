param(
    [string] $MarkdownPath = "reports/latest-100-saved-audit-2026-08-26.md",
    [string] $OutputPath = "reports/latest-100-saved-audit-2026-08-26.txt"
)

$ErrorActionPreference = "Stop"
$lines = Get-Content -LiteralPath $MarkdownPath
$rows = foreach ($line in $lines) {
    if ($line -notmatch '^\|\s*(PASS|REVIEW|FAIL)\s*\|') { continue }
    $parts = $line.Trim('|').Split('|') | ForEach-Object { $_.Trim() }
    if ($parts.Count -lt 10) { continue }
    [pscustomobject]@{
        Result = $parts[0]
        ImportId = $parts[1]
        JobId = $parts[2]
        SavedAt = $parts[3]
        File = $parts[4]
        Permit = $parts[5]
        Target = $parts[6]
        Details = $parts[7]
        Revision = $parts[8]
        Finding = $parts[9]
    }
}

$pass = @($rows | Where-Object Result -eq PASS).Count
$review = @($rows | Where-Object Result -eq REVIEW).Count
$fail = @($rows | Where-Object Result -eq FAIL).Count
$builder = [Text.StringBuilder]::new()
$null = $builder.AppendLine("AeroSync - DOI CHIEU 100 FILE SAVED MOI NHAT")
$null = $builder.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
$null = $builder.AppendLine("Nguon: $MarkdownPath")
$null = $builder.AppendLine("Ket qua cau truc: PASS=$pass; REVIEW=$review; FAIL=$fail")
$null = $builder.AppendLine("Luu y: PASS chi khẳng dinh cac truong va quan he DB co ban; khong thay the doi chieu nghiep vu tung truong voi file nguon.")
$null = $builder.AppendLine()
$null = $builder.AppendLine("[FAIL] Can sua/rollback/replay")
foreach ($row in $rows | Where-Object Result -eq FAIL) {
    $null = $builder.AppendLine("Job $($row.JobId) | $($row.File) | $($row.Permit) | $($row.Target) | $($row.Details) | $($row.Finding)")
}
$null = $builder.AppendLine()
$null = $builder.AppendLine("[REVIEW] Can doi chieu bo sung")
foreach ($row in $rows | Where-Object Result -eq REVIEW) {
    $null = $builder.AppendLine("Job $($row.JobId) | $($row.File) | $($row.Permit) | $($row.Target) | $($row.Details) | $($row.Finding)")
}
$null = $builder.AppendLine()
$null = $builder.AppendLine("[PASS] Dat kiem tra cau truc")
foreach ($row in $rows | Where-Object Result -eq PASS) {
    $null = $builder.AppendLine("Job $($row.JobId) | $($row.File) | $($row.Permit) | $($row.Target) | $($row.Details)")
}
$null = $builder.AppendLine()
$null = $builder.AppendLine("SEMANTIC FINDINGS DA XAC NHAN")
$null = $builder.AppendLine("Job 14356: THY source co 4 schedule moi, target chi co 1 dong va sai route/craft/purpose.")
$null = $builder.AppendLine("Job 14344: Application for Revision da bi ghi thanh permit SC (du lieu lich su truoc khi sua detector).")
$null = $builder.AppendLine("Jobs 14341,14361: revision QQE325 con dong cu/duplicate va thieu RVS/Rn trong reference.")
$null = $builder.AppendLine("Job 14314: source co 29 flight, import report 1 row; target 29 row do tich luy/khong the tin cay mapping hien tai.")
$null = $builder.AppendLine("Job 14289: revision LD-3062 dang vao NO thay vi SC va so dong khong khop.")
[IO.File]::WriteAllText($OutputPath, $builder.ToString(), [Text.UTF8Encoding]::new($false))
Write-Output "Wrote $OutputPath"
