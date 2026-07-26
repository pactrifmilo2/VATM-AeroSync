param(
    [Parameter(Mandatory = $true)]
    [string] $JarName
)

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"
$jar = Join-Path $root "$JarName\target\$JarName-0.0.1-SNAPSHOT.jar"

if (-not (Test-Path $envFile)) {
    Write-Host "Missing .env — run: .\run-aerosync.bat  (creates .env on first run)"
    exit 1
}

if (-not (Test-Path $jar)) {
    Write-Host "Missing $jar — run from repo root:"
    Write-Host "  .\aerosync-worker\mvnw.cmd package -DskipTests"
    exit 1
}

function Import-DotEnv {
    param([string] $Path)
    $values = @{}
    foreach ($line in Get-Content $Path) {
        if ($line -match "^\s*#") { continue }
        if ($line -match "^\s*$") { continue }
        if ($line -match "^\s*([^=]+?)\s*=\s*(.*)\s*$") {
            $name = $Matches[1].Trim()
            $value = $Matches[2].Trim().Trim('"').Trim("'")
            $values[$name] = $value
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

Set-Location $root
$env:AEROSYNC_CONFIG_DIR = $root
Import-DotEnv -Path $envFile
Write-Host "Starting $JarName (loading $envFile) ..."
java -jar $jar
