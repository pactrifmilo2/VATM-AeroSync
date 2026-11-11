#Requires -Version 5.1
param(
    [switch] $SkipBuild,
    [string] $WinSwPath = "E:\AeroSyncServices\WinSW-x64.exe",
    [string] $OutputDirectory = "dist",
    [string] $UiPublishDirectory = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$mvnw = Join-Path $root "aerosync-worker\mvnw.cmd"
$uiProject = Join-Path $root "aerosync-ui\AeroSync.UI.csproj"
$uiPublish = Join-Path $root "aerosync-ui\bin\x64\Release\net8.0-windows10.0.19041.0\win-x64\publish"
if ($UiPublishDirectory) { $uiPublish = [IO.Path]::GetFullPath($UiPublishDirectory) }
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$version = "0.0.1-$timestamp"
$outputRoot = Join-Path $root $OutputDirectory
$stage = Join-Path $outputRoot "aerosync-$version"
$zip = "$stage.zip"

Set-Location $root
if (-not $SkipBuild) {
    & $mvnw -f (Join-Path $root "pom.xml") package "-DskipTests" `
        -pl aerosync-ingest,aerosync-worker,aerosync-api -am
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }

    & dotnet publish $uiProject -c Release -p:Platform=x64 -r win-x64 --self-contained true `
        -p:WindowsAppSDKSelfContained=true -o $uiPublish
    if ($LASTEXITCODE -ne 0) { throw "UI publish failed with exit code $LASTEXITCODE" }
}

$artifacts = [ordered]@{
    "aerosync-api\target\aerosync-api-0.0.1-SNAPSHOT.jar" = "api.jar"
    "aerosync-ingest\target\aerosync-ingest-0.0.1-SNAPSHOT.jar" = "ingest.jar"
    "aerosync-worker\target\aerosync-worker-0.0.1-SNAPSHOT-deploy.jar" = "worker.jar"
}
foreach ($source in $artifacts.Keys) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $source) -PathType Leaf)) {
        throw "Missing build artifact: $source"
    }
}
function Test-WorkerProfiles {
    param([string] $WorkerJar)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $yamlRoot = Join-Path $root "aerosync-worker\src\main\resources\permit-formats"
    $jarInfo = Get-Item -LiteralPath $WorkerJar
    $newestYaml = Get-ChildItem -LiteralPath $yamlRoot -Filter *.yaml -File |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($newestYaml -and $newestYaml.LastWriteTimeUtc -gt $jarInfo.LastWriteTimeUtc) {
        throw "worker.jar is older than permit-formats YAML files. Remove -SkipBuild and rebuild."
    }
    $archive = [IO.Compression.ZipFile]::OpenRead($WorkerJar)
    try {
        $jarEntries = @($archive.Entries | Where-Object {
            $_.FullName -like "BOOT-INF/classes/permit-formats/*.yaml"
        } | ForEach-Object { Split-Path $_.FullName -Leaf })
    } finally {
        $archive.Dispose()
    }
    $sourceNames = @(Get-ChildItem -LiteralPath $yamlRoot -Filter *.yaml -File |
        Select-Object -ExpandProperty Name)
    $missing = @($sourceNames | Where-Object { $_ -notin $jarEntries })
    if ($missing.Count -gt 0) {
        throw "worker.jar thieu $($missing.Count) permit-format YAML: $($missing -join ', ')"
    }
    Write-Host ("Worker profiles: {0} YAML files embedded" -f $jarEntries.Count)
}
Test-WorkerProfiles (Join-Path $root "aerosync-worker\target\aerosync-worker-0.0.1-SNAPSHOT-deploy.jar")
if (-not (Test-Path -LiteralPath $WinSwPath -PathType Leaf)) {
    throw "WinSW not found at $WinSwPath. Use -WinSwPath to specify WinSW-x64.exe."
}
if (-not (Test-Path -LiteralPath (Join-Path $uiPublish "AeroSync.UI.exe") -PathType Leaf)) {
    throw "Published UI not found at $uiPublish. Run without -SkipBuild or publish the UI first."
}

# WinUI unpackaged publish may omit the app PRI even when the build output
# contains it. XAML resources cannot load without this file at runtime.
$uiPri = Join-Path $uiPublish "AeroSync.UI.pri"
if (-not (Test-Path -LiteralPath $uiPri -PathType Leaf)) {
    $uiPri = Join-Path (Split-Path -Parent $uiPublish) "AeroSync.UI.pri"
}
if (-not (Test-Path -LiteralPath $uiPri -PathType Leaf)) {
    throw "Missing AeroSync.UI.pri (WinUI XAML resources). Publish the UI again."
}

if (Test-Path -LiteralPath $stage) {
    throw "Release directory already exists: $stage"
}
New-Item -ItemType Directory -Force -Path (Join-Path $stage "app"),
    (Join-Path $stage "config"), (Join-Path $stage "deploy"),
    (Join-Path $stage "tools"), (Join-Path $stage "ui") | Out-Null
Copy-Item -LiteralPath (Join-Path $root "deployment\AeroSync-Deploy.cmd") -Destination $stage
Copy-Item -LiteralPath (Join-Path $root "deployment\HUONG-DAN.txt") -Destination $stage
Copy-Item -LiteralPath (Join-Path $root "deployment\DEPLOYMENT-GUIDE.md") -Destination $stage
Copy-Item -LiteralPath (Join-Path $root "deployment\deploy\AeroSync-Deploy.ps1") `
    -Destination (Join-Path $stage "deploy")
Copy-Item -LiteralPath (Join-Path $root "deployment\deploy\Run-Service.ps1") `
    -Destination (Join-Path $stage "deploy")
Copy-Item -LiteralPath (Join-Path $root "deployment\deploy\Open-AeroSync.ps1") `
    -Destination (Join-Path $stage "deploy")
Copy-Item -LiteralPath (Join-Path $root ".env.example") `
    -Destination (Join-Path $stage "config\.env.example")
Copy-Item -LiteralPath (Join-Path $root "scripts\migrate-atfm-purpose-craft.sql") `
    -Destination (Join-Path $stage "tools\migrate-atfm-purpose-craft.sql")
Copy-Item -LiteralPath (Join-Path $root "scripts\migrate-aerosync-runtime-config.sql") `
    -Destination (Join-Path $stage "tools\migrate-aerosync-runtime-config.sql")
Copy-Item -LiteralPath (Join-Path $root "scripts\migrate-atfm-revision-backup.sql") `
    -Destination (Join-Path $stage "tools\migrate-atfm-revision-backup.sql")
Copy-Item -LiteralPath $WinSwPath -Destination (Join-Path $stage "tools\WinSW-x64.exe")
foreach ($entry in $artifacts.GetEnumerator()) {
    Copy-Item -LiteralPath (Join-Path $root $entry.Key) `
        -Destination (Join-Path $stage "app\$($entry.Value)")
}
Copy-Item -Path (Join-Path $uiPublish "*") -Destination (Join-Path $stage "ui") -Recurse -Force
Copy-Item -LiteralPath $uiPri -Destination (Join-Path $stage "ui\AeroSync.UI.pri") -Force
Copy-Item -LiteralPath (Join-Path $root "aerosync-ui\.env.example") `
    -Destination (Join-Path $stage "ui\.env") -Force
Set-Content -LiteralPath (Join-Path $stage "VERSION") -Value $version -Encoding ASCII

$hashLines = Get-ChildItem -LiteralPath $stage -Recurse -File |
    Where-Object { $_.Name -ne "SHA256SUMS.txt" } |
    ForEach-Object {
        $relative = $_.FullName.Substring($stage.Length + 1)
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        "$hash  $relative"
    }
$hashLines | Set-Content -LiteralPath (Join-Path $stage "SHA256SUMS.txt") -Encoding ASCII

if (Test-Path -LiteralPath $zip) { throw "Release ZIP already exists: $zip" }
[IO.Compression.ZipFile]::CreateFromDirectory($stage, $zip,
    [IO.Compression.CompressionLevel]::Optimal, $true)
Write-Host ""
Write-Host "Release ready:" -ForegroundColor Green
Write-Host "  $zip"
Write-Host ""
Write-Host "Target user only needs to extract the ZIP and double-click AeroSync-Deploy.cmd."
