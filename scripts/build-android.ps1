param([switch]$Check, [switch]$Package, [switch]$Stacktrace, [switch]$Info)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/toolchain.ps1"
Initialize-AndroidBuild
$gradle = (Get-GradleDistribution).Executable
$tasks = @('assembleDebug')
if ($Check -or $Package) { $tasks += @('lintDebug', 'testDebugUnitTest') }
$diagnostics = @()
if ($Stacktrace) { $diagnostics += '--stacktrace' }
if ($Info) { $diagnostics += '--info' }
Write-Host "Using repository-local Gradle: $gradle"
& $gradle -p "$projectRoot/android" --no-daemon --console=plain @diagnostics @tasks
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
$apk = Join-Path $projectRoot 'android/app/build/outputs/apk/debug/app-debug.apk'
Write-Host "APK: $apk"
if (!$Package) { return }

$metadata = Get-Content -LiteralPath (Join-Path $projectRoot 'package.json') -Raw | ConvertFrom-Json
$version = $metadata.version
$gradleConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'android/app/build.gradle.kts') -Raw
if ($version -notmatch '^\d+\.\d+\.\d+$' -or $gradleConfig -notmatch ('versionName = "' + [regex]::Escape($version) + '"')) {
    throw 'Android and desktop versions must match for packaging.'
}
$sdk = Resolve-AndroidSdk
$signerPath = Get-AndroidSdkRequirements | ForEach-Object { $_.Files } | Where-Object { $_ -like '*/apksigner.bat' } |
    Select-Object -First 1
if (!$signerPath) { throw 'The SDK build-tool requirements do not specify apksigner.' }
$signer = Join-Path $sdk $signerPath
& $signer verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

$artifactDir = Join-Path $projectRoot 'dist'
[void][IO.Directory]::CreateDirectory($artifactDir)
$artifact = Join-Path $artifactDir 'CompassLink3D.apk'
$digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$pending = Join-Path $artifactDir ([guid]::NewGuid().ToString() + '.apk')
try {
    [IO.File]::Copy($apk, $pending)
    if (Test-Path -LiteralPath $artifact) {
        [IO.File]::Replace($pending, $artifact, [NullString]::Value)
    } else {
        [IO.File]::Move($pending, $artifact)
    }
} finally {
    [IO.File]::Delete($pending)
}
[IO.File]::WriteAllText("$artifact.sha256", "$digest  $([IO.Path]::GetFileName($artifact))`r`n", [Text.UTF8Encoding]::new($false))
Write-Host "Verified debug package: $artifact"
