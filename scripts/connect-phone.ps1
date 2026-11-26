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
. "$PSScriptRoot/toolchain.ps1"
$adb = Resolve-Adb

function Assert-Endpoint([string]$Endpoint) {
    if ($Endpoint.Length -gt 259 -or $Endpoint -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.-]*:[0-9]{1,5}$') {
        throw 'Enter the phone IP address and port, for example 192.168.1.20:37123.'
    }
    $port = [int]($Endpoint.Split(':')[-1])
    if ($port -lt 1 -or $port -gt 65535) { throw 'Port must be between 1 and 65535.' }
}

& $adb start-server
if ($LASTEXITCODE -ne 0) {
    throw 'ADB server could not start on PC port 5037. Check other ADB instances and local networking restrictions.'
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
    $serial = $serial.Trim()
}

$deviceState = & $adb -s $serial get-state
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') { throw 'Phone is unauthorized or offline. Check debugging settings and reconnect.' }
if ($SkipInstall) {
    Write-Host 'Skipping APK installation. The prebuilt CompassLink companion must already be installed on your phone.'
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
        & $adb -s $serial install --no-streaming -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }
    } else {
        Write-Host 'Companion already installed; skipping installation. Use -Reinstall after rebuilding the app.'
    }
}
& $adb -s $serial reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) { throw "$Mode port forwarding failed" }
$modeValue = $Mode.ToLowerInvariant()
$tokenBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($tokenBytes) } finally { $generator.Dispose() }
$controllerToken = ([BitConverter]::ToString($tokenBytes) -replace '-', '').ToLowerInvariant()
$tokenDirectory = Join-Path $projectRoot '.tools'
$tokenPath = Join-Path $tokenDirectory 'controller-authorization.json'
$pendingPath = Join-Path $tokenDirectory ([guid]::NewGuid().ToString() + '.tmp')
[void][System.IO.Directory]::CreateDirectory($tokenDirectory)
try {
    $grant = @{ token = $controllerToken; mode = $modeValue } | ConvertTo-Json -Compress
    [System.IO.File]::WriteAllText($pendingPath, $grant)
    if ([System.IO.File]::Exists($tokenPath)) {
        [System.IO.File]::Replace($pendingPath, $tokenPath, [NullString]::Value)
    } else {
        [System.IO.File]::Move($pendingPath, $tokenPath)
    }
} finally {
    [System.IO.File]::Delete($pendingPath)
}
$launchArgs = @('-s', $serial, 'shell', 'am', 'start', '--activity-clear-top', '--activity-single-top',
    '-n', 'com.compasslink3d/.MainActivity', '--es', 'connection_mode', $modeValue,
    '--es', 'controller_token', $controllerToken, '2>&1')
$launchOutput = & $adb @launchArgs
if ($LASTEXITCODE -eq 0 -and ($launchOutput -match 'its current task has been brought to the front')) {
    # Some Android builds bring a background task forward without delivering the extras.
    Start-Sleep -Milliseconds 250
    $launchOutput = & $adb @launchArgs
}
if ($LASTEXITCODE -ne 0 -or ($launchOutput -match 'Error:|Error type|Exception')) {
    throw 'Unable to open companion app. If using -SkipInstall, install the prebuilt CompassLink APK on the phone first. See the README quick start.'
}
Write-Host "$Mode tunnel ready. Run npm start, open http://127.0.0.1:8000, and open Sensors or Controllers on your phone."
