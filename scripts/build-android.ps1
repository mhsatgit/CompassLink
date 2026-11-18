$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = "$projectRoot/.tools/jdk"
$env:ANDROID_HOME = "$projectRoot/.tools/android-sdk"
$env:GRADLE_USER_HOME = "$projectRoot/.tools/gradle-cache"
if (!(Test-Path "$env:JAVA_HOME/bin/java.exe")) { throw 'Run npm run setup:android first' }
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$gradle = "$projectRoot/.tools/gradle-8.9/bin/gradle.bat"
if (!(Test-Path $gradle)) { $gradle = "$projectRoot/android/gradlew.bat" }
& $gradle -p "$projectRoot/android" --no-daemon assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
Write-Host "APK: $projectRoot/android/app/build/outputs/apk/debug/app-debug.apk"
