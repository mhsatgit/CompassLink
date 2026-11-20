param([switch]$Check, [switch]$Stacktrace, [switch]$Info)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/toolchain.ps1"
Initialize-AndroidBuild
$gradle = (Get-GradleDistribution).Executable
$tasks = @('assembleDebug')
if ($Check) { $tasks += @('lintDebug', 'testDebugUnitTest') }
$diagnostics = @()
if ($Stacktrace) { $diagnostics += '--stacktrace' }
if ($Info) { $diagnostics += '--info' }
Write-Host "Using repository-local Gradle: $gradle"
& $gradle -p "$projectRoot/android" --no-daemon --console=plain @diagnostics @tasks
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
Write-Host "APK: $projectRoot/android/app/build/outputs/apk/debug/app-debug.apk"
