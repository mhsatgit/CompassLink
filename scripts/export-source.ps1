param()

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$metadata = Get-Content -LiteralPath (Join-Path $root 'package.json') -Raw | ConvertFrom-Json
if ($metadata.version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid package version.' }
$directory = Join-Path $root 'dist'
[void][IO.Directory]::CreateDirectory($directory)
$target = Join-Path $directory "CompassLink3D-v$($metadata.version)-source.zip"
$pending = Join-Path $directory ([guid]::NewGuid().ToString() + '.zip')
try {
    $exclusions = Join-Path $root '.sourceignore'
    & tar.exe -a -cf $pending -X $exclusions -C $root .
    if ($LASTEXITCODE -ne 0) { throw 'Could not create source export.' }
    $archiveEntries = & tar.exe -tf $pending
    if ($LASTEXITCODE -ne 0) { throw 'Could not read source export.' }
    $entries = $archiveEntries -replace '^\./', ''
    foreach ($required in 'package-lock.json', 'README.md', 'android/gradle/wrapper/gradle-wrapper.jar') {
        if ($entries -notcontains $required) { throw "Source export is missing $required." }
    }
    if ($entries -match '(?:^|/)(?:\.git|\.tools|profiles|node_modules|bin|obj|build|dist)(?:/|$)|(?:\.apk|\.apk\.sha256|\.jks|\.keystore|\.pfx|\.idsig|controller-authorization\.json)$') {
        throw 'The source export contains local state, dependencies or generated artifacts.'
    }
    Move-Item -LiteralPath $pending -Destination $target -Force
    Write-Host "Source export: $target"
} finally {
    [IO.File]::Delete($pending)
}
