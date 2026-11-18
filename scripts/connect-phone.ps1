$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = "$projectRoot/.tools/android-sdk/platform-tools/adb.exe"
if (!(Test-Path $adb)) { throw 'Run npm run setup:android first' }
$serial = & $adb -d get-serialno
if ($LASTEXITCODE -ne 0 -or !$serial -or $serial -eq 'unknown') { throw 'Connect exactly one authorized USB debugging phone; unlock it and accept the USB prompt.' }
$apk = "$projectRoot/android/app/build/outputs/apk/debug/app-debug.apk"
if (!(Test-Path $apk)) { throw 'Run npm run build:android first' }
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }
& $adb -s $serial reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) { throw 'USB port forwarding failed' }
& $adb -s $serial shell am start -n com.compasslink3d/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Unable to open companion app' }
Write-Host 'USB tunnel ready. Run npm start, open http://127.0.0.1:8000, and tap Start streaming on your phone.'
