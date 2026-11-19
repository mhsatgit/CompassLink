param(
    [ValidateSet('USB', 'Wireless')]
    [string]$Mode = 'USB',
    [string]$Address,
    [switch]$Pair,
    [string]$PairAddress,
    [switch]$Reinstall,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
if ($SkipInstall -and $Reinstall) { throw 'Use either -SkipInstall or -Reinstall, not both.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = "$projectRoot/.tools/android-sdk/platform-tools/adb.exe"
if (!(Test-Path $adb)) { throw 'Run npm run setup:adb for the prebuilt app, or npm run setup:android to build from source.' }

function Assert-Endpoint([string]$Endpoint) {
    if ($Endpoint -notmatch '^[a-zA-Z0-9.-]+:\d{1,5}$') {
        throw 'Enter the phone IP address and port, for example 192.168.1.20:37123.'
    }
    $port = [int]($Endpoint.Split(':')[-1])
    if ($port -lt 1 -or $port -gt 65535) { throw 'Port must be between 1 and 65535.' }
}

if ($Mode -eq 'Wireless') {
    Write-Host 'Connect PC and phone to the same Wi-Fi. Enable Developer options > Wireless debugging (Android 11+).'
    if ($Pair -or $PairAddress) {
        if (!$PairAddress) { $PairAddress = Read-Host 'Open Pair device with pairing code and enter IP address:pairing port' }
        Assert-Endpoint $PairAddress
        # ADB reads the pairing code interactively; it is never stored here.
        & $adb pair $PairAddress
        if ($LASTEXITCODE -ne 0) { throw 'Pairing failed. Open a fresh pairing dialog and try again.' }
    }
    if (!$Address) { $Address = Read-Host 'Enter IP address:port from the main Wireless debugging page (not the pairing port)' }
    Assert-Endpoint $Address
    & $adb connect $Address
    if ($LASTEXITCODE -ne 0) { throw 'Wireless connection failed. Check Wi-Fi and the current debugging port.' }
    $serial = $Address
} else {
    $serial = & $adb -d get-serialno
    if ($LASTEXITCODE -ne 0 -or !$serial -or $serial -eq 'unknown') {
        throw 'Connect exactly one authorized USB debugging phone; unlock it and accept the USB prompt.'
    }
}

$deviceState = & $adb -s $serial get-state
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') { throw 'Phone is unauthorized or offline. Check debugging settings and reconnect.' }
if ($SkipInstall) {
    Write-Host 'Skipping APK installation. Install apk/CompassLink.apk on your phone manually before connecting.'
} else {
    $installed = @()
    if (!$Reinstall) {
        # pm path exits with code 1 when the app is absent on a first connection.
        # Listing packages succeeds with no matches, keeping that case distinct
        # from an actual ADB/package-manager failure. Check the active Android user.
        $installed = & $adb -s $serial shell pm list packages --user current com.compasslink3d
        if ($LASTEXITCODE -ne 0) { throw 'Unable to check the companion installation.' }
    }
    if ($Reinstall -or !($installed -match '^package:com\.compasslink3d\s*$')) {
        $apk = "$projectRoot/android/app/build/outputs/apk/debug/app-debug.apk"
        if (!(Test-Path $apk)) { throw 'Run npm run build:android first' }
        & $adb -s $serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }
    } else {
        Write-Host 'Companion already installed; skipping installation. Use -Reinstall after rebuilding the app.'
    }
}
& $adb -s $serial reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) { throw "$Mode port forwarding failed" }
$modeValue = $Mode.ToLowerInvariant()
& $adb -s $serial shell am start -n com.compasslink3d/.MainActivity --es connection_mode $modeValue
if ($LASTEXITCODE -ne 0) { throw 'Unable to open companion app. If using -SkipInstall, install apk/CompassLink.apk on the phone first.' }
Write-Host "$Mode tunnel ready. Run npm start, open http://127.0.0.1:8000, and tap Start streaming on your phone."
