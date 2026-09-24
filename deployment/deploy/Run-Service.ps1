param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("api", "ingest", "worker")]
    [string] $App
)

$ErrorActionPreference = "Stop"
$installRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $installRoot "config\.env"
$javaFile = Join-Path $installRoot "runtime\java-path.txt"
$jar = Join-Path $installRoot "app\$App.jar"

function Import-DotEnv {
    param([string] $Path)
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*(?:#|$)') { continue }
        if ($line -match '^\s*([^=]+?)\s*=\s*(.*)\s*$') {
            $values[$Matches[1].Trim()] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }
    foreach ($name in $values.Keys) {
        $value = $values[$name]
        if ($value -match '^\$\{([A-Z][A-Z0-9_]*)\}$' -and $values.ContainsKey($Matches[1])) {
            $value = $values[$Matches[1]]
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing configuration: $envFile"
}
if (-not (Test-Path -LiteralPath $javaFile -PathType Leaf)) {
    throw "Missing Java runtime selection: $javaFile"
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Missing application: $jar"
}

$java = (Get-Content -LiteralPath $javaFile -Raw -Encoding UTF8).Trim()
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "Java executable no longer exists: $java"
}

Import-DotEnv -Path $envFile
$env:AEROSYNC_CONFIG_DIR = Join-Path $installRoot "config"
Set-Location $installRoot
& $java -jar $jar
exit $LASTEXITCODE
