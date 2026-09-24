param(
    [int] $TargetCount = 1000,
    [long] $SnapshotMaxMetadataId,
    [long] $ResendMetadataBoundary,
    [string] $ApiBaseUrl = "http://localhost:8081/api/reports/emails",
    [int] $Parallelism = 4
)

$ErrorActionPreference = "Stop"

function Import-DotEnv {
    param([string] $Path)
    $result = @{}
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]+?)\s*=\s*(.*)\s*$') {
            $result[$Matches[1].Trim()] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $result
}

function Resolve-DotEnvValue {
    param([hashtable] $Values, [string] $Name)
    $value = $Values[$Name]
    if ($value -match '^\$\{([A-Z][A-Z0-9_]*)\}$') {
        return $Values[$Matches[1]]
    }
    return $value
}

function Invoke-RedisRaw {
    param([string[]] $Parts)
    $client = [Net.Sockets.TcpClient]::new('127.0.0.1', 6379)
    try {
        $stream = $client.GetStream()
        $builder = [Text.StringBuilder]::new()
        [void] $builder.Append('*').Append($Parts.Count).Append("`r`n")
        foreach ($part in $Parts) {
            [void] $builder.Append('$').Append($part.Length).Append("`r`n").Append($part).Append("`r`n")
        }
        $bytes = [Text.Encoding]::ASCII.GetBytes($builder.ToString())
        $stream.Write($bytes, 0, $bytes.Length)
        $buffer = New-Object byte[] 1048576
        $read = $stream.Read($buffer, 0, $buffer.Length)
        return [Text.Encoding]::ASCII.GetString($buffer, 0, $read)
    } finally {
        $client.Dispose()
    }
}

function Get-AuthorizedJobIds {
    $result = [System.Collections.Generic.HashSet[long]]::new()
    $raw = Invoke-RedisRaw -Parts @('KEYS', 'aerosync:resend:*')
    $keys = @(($raw -split "`r`n") | Where-Object { $_ -like 'aerosync:resend:*' })
    foreach ($key in $keys) {
        $value = ((Invoke-RedisRaw -Parts @('GET', $key)) -split "`r`n")[1]
        if ($value) { [void] $result.Add([long] $value) }
    }
    return $result
}

function Add-ReingestedJobIds {
    param(
        [System.Collections.Generic.HashSet[long]] $JobIds,
        [string] $Sender
    )
    $page = 0
    $done = $false
    while ($page -lt 100 -and -not $done) {
        $response = Invoke-RestMethod -Uri ("{0}?page={1}&size=100" -f $ApiBaseUrl, $page) -TimeoutSec 30
        foreach ($row in $response.content) {
            if ([long] $row.id -le $ResendMetadataBoundary) {
                $done = $true
                break
            }
            if ($row.syncJobId -and $row.sender -and
                    $row.sender.Equals($Sender, [StringComparison]::OrdinalIgnoreCase)) {
                [void] $JobIds.Add([long] $row.syncJobId)
            }
        }
        if (-not $response.hasNext) { break }
        $page++
    }
}

function Get-OracleConnectString {
    param([string] $JdbcUrl)
    if ($JdbcUrl -match '^jdbc:oracle:thin:@//([^:/]+):(\d+)/([^?]+)') {
        return "$($Matches[1]):$($Matches[2])/$($Matches[3])"
    }
    if ($JdbcUrl -match '^jdbc:oracle:thin:@([^:/]+):(\d+):([^?]+)') {
        return "$($Matches[1]):$($Matches[2]):$($Matches[3])"
    }
    throw "Unsupported Oracle JDBC URL"
}

function Get-VerifiedCandidates {
    param(
        [hashtable] $EnvValues,
        [System.Collections.Generic.HashSet[long]] $ExcludedJobIds,
        [int] $Limit = 1000
    )
    $url = Resolve-DotEnvValue $EnvValues 'SPRING_DATASOURCE_URL'
    $user = Resolve-DotEnvValue $EnvValues 'SPRING_DATASOURCE_USERNAME'
    $password = Resolve-DotEnvValue $EnvValues 'SPRING_DATASOURCE_PASSWORD'
    $connect = Get-OracleConnectString $url
    $sql = @"
WHENEVER SQLERROR EXIT SQL.SQLCODE
CONNECT $user/"$password"@$connect
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON
SELECT em.id || '|' || em.sync_job_id || '|' || em.processing_status || '|' || em.message_id || '|' || fr.stored_path
FROM email_metadata em
JOIN sync_jobs sj ON sj.id = em.sync_job_id
JOIN (
  SELECT sync_job_id, stored_path,
         ROW_NUMBER() OVER (PARTITION BY sync_job_id ORDER BY created_at DESC, id DESC) rn
  FROM file_records
) fr ON fr.sync_job_id = em.sync_job_id AND fr.rn = 1
WHERE em.id <= $SnapshotMaxMetadataId
  AND em.processing_status IN ('FAILED','QUARANTINED','SAVED','SKIPPED')
  AND sj.status IN ('SUCCESS','FAILED','SKIPPED','QUARANTINED')
  AND (LOWER(em.attachment_name) LIKE '%.doc'
       OR LOWER(em.attachment_name) LIKE '%.docx'
       OR LOWER(em.attachment_name) LIKE '%.docm'
       OR LOWER(em.attachment_name) LIKE '%.dot'
       OR LOWER(em.attachment_name) LIKE '%.dotx')
ORDER BY em.received_at DESC, em.id DESC;
EXIT
"@
    $lines = @($sql | sqlplus -L -S /nolog 2>&1 | Where-Object { $_ -match '^\s*\d+\|' })
    $seen = [System.Collections.Generic.HashSet[long]]::new()
    $candidates = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $lines) {
        $parts = $line.Trim() -split '\|', 5
        $jobId = [long] $parts[1]
        if ($ExcludedJobIds.Contains($jobId) -or -not $seen.Add($jobId)) { continue }
        if (-not (Test-Path -LiteralPath $parts[4] -PathType Leaf -ErrorAction SilentlyContinue)) { continue }
        $candidates.Add([pscustomobject]@{
            AttachmentId = [long] $parts[0]
            JobId = $jobId
            Status = $parts[2]
            MessageId = $parts[3]
        })
        if ($candidates.Count -ge $Limit) { break }
    }
    return $candidates
}

function Send-CandidateWave {
    param([object[]] $Wave)
    $partitions = @()
    1..$Parallelism | ForEach-Object { $partitions += , @() }
    for ($index = 0; $index -lt $Wave.Count; $index++) {
        $partitions[$index % $Parallelism] += $Wave[$index]
    }
    $jobs = @()
    for ($worker = 0; $worker -lt $Parallelism; $worker++) {
        $json = ConvertTo-Json -InputObject @($partitions[$worker]) -Depth 5 -Compress
        $jobs += Start-Job -ArgumentList $json, $ApiBaseUrl -ScriptBlock {
            param($json, $apiBaseUrl)
            $items = ConvertFrom-Json -InputObject $json
            for ($index = 0; $index -lt $items.Count; $index++) {
                $item = $items[$index]
                $payload = @{
                    messageId = [string] $item.MessageId
                    statuses = @([string] $item.Status)
                    attachmentIds = @([long] $item.AttachmentId)
                } | ConvertTo-Json -Depth 4 -Compress
                try {
                    $response = Invoke-RestMethod -Method Post -Uri ($apiBaseUrl + '/resend') `
                        -ContentType 'application/json; charset=utf-8' -Body $payload -TimeoutSec 90
                    [pscustomobject]@{
                        Success = $response.accepted -and [int] $response.attachmentsSent -eq 1
                        JobId = [long] $item.JobId
                    }
                } catch {
                    [pscustomobject]@{ Success = $false; JobId = [long] $item.JobId }
                }
            }
        }
    }
    Wait-Job -Job $jobs | Out-Null
    $results = @($jobs | Receive-Job)
    $jobs | Remove-Job
    return $results
}

$root = Split-Path -Parent $PSScriptRoot
$envValues = Import-DotEnv (Join-Path $root '.env')
if (-not $SnapshotMaxMetadataId -or -not $ResendMetadataBoundary) {
    throw 'SnapshotMaxMetadataId and ResendMetadataBoundary are required for an exact resumable batch'
}

$accepted = Get-AuthorizedJobIds
$resendSender = Resolve-DotEnvValue $envValues 'APP_EMAIL_RESEND_USERNAME'
Add-ReingestedJobIds -JobIds $accepted -Sender $resendSender
$candidates = Get-VerifiedCandidates -EnvValues $envValues -ExcludedJobIds $accepted -Limit 2000
$cursor = 0
$attempts = 0
$failures = 0
Write-Output "RESEND_START accepted=$($accepted.Count) needed=$($TargetCount - $accepted.Count) verifiedCandidates=$($candidates.Count)"

while ($accepted.Count -lt $TargetCount) {
    $needed = $TargetCount - $accepted.Count
    if ($cursor -ge $candidates.Count) { throw "Verified candidates exhausted; still need $needed" }
    $take = [Math]::Min($needed, $candidates.Count - $cursor)
    $wave = @($candidates[$cursor..($cursor + $take - 1)])
    $cursor += $take
    $results = Send-CandidateWave -Wave $wave
    $attempts += $results.Count
    $waveSuccesses = 0
    foreach ($result in $results) {
        if ($result.Success) {
            [void] $accepted.Add([long] $result.JobId)
            $waveSuccesses++
        } else {
            $failures++
        }
    }
    Write-Output "RESEND_WAVE attempts=$attempts accepted=$($accepted.Count) remaining=$($TargetCount - $accepted.Count) failures=$failures"
    if ($waveSuccesses -eq 0) {
        throw 'SMTP circuit breaker: an entire verified-file wave failed; stop and review the Gmail sending limit before resuming'
    }
}

Write-Output "RESEND_RESULT acceptedFiles=$($accepted.Count) attempts=$attempts failures=$failures"
