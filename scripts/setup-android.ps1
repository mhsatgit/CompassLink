param([switch]$PlatformToolsOnly)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null

function Get-Archive($url, $name, $destination, $sha1 = '') {
    $archive = Join-Path $toolsRoot $name
    if (!(Test-Path -LiteralPath $archive) -or (Get-Item -LiteralPath $archive).Length -eq 0) {
        Write-Host "Downloading $name"
        & node "$PSScriptRoot/download.cjs" $url "$archive.partial"
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $name" }
        Move-Item -LiteralPath "$archive.partial" -Destination $archive -Force
    }
    if ($sha1) {
        $stream = [System.IO.File]::OpenRead($archive)
        $hasher = [System.Security.Cryptography.SHA1]::Create()
        try { $actualHash = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '') }
        finally { $stream.Dispose(); $hasher.Dispose() }
        if ($actualHash -ne $sha1) { throw "Archive checksum mismatch: $name" }
    }
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $archive -C $destination
        if ($LASTEXITCODE -ne 0) { throw "Archive extraction failed: $name" }
    } else {
        Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
    }
}

if ($PlatformToolsOnly) {
    if (!(Test-Path "$toolsRoot/android-sdk/platform-tools/adb.exe")) {
        Get-Archive 'https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip' 'platform-tools_r37.0.1-win.zip' "$toolsRoot/android-sdk" 'e03e78b1d80b396f1c3358e31251cb31740e1110'
    }
    Write-Host 'ADB installed. Install apk/CompassLink.apk on your phone, then run a connect:prebuilt command.'
    return
}

if (!(Test-Path "$toolsRoot/jdk")) {
    Get-Archive 'https://aka.ms/download-jdk/microsoft-jdk-17.0.17-windows-x64.zip' 'jdk.zip' "$toolsRoot/java-extract"
    $jdkFolder = Get-ChildItem "$toolsRoot/java-extract" -Directory | Select-Object -First 1
    Move-Item -LiteralPath $jdkFolder.FullName -Destination "$toolsRoot/jdk"
}
$env:JAVA_HOME = "$toolsRoot/jdk"
$env:ANDROID_HOME = "$toolsRoot/android-sdk"
$env:GRADLE_USER_HOME = "$toolsRoot/gradle-cache"
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"

if (!(Test-Path "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat")) {
    Get-Archive 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' 'android-commandline.zip' "$toolsRoot/sdk-extract"
    New-Item -ItemType Directory -Force "$env:ANDROID_HOME/cmdline-tools" | Out-Null
    Move-Item "$toolsRoot/sdk-extract/cmdline-tools" "$env:ANDROID_HOME/cmdline-tools/latest"
}
if (!(Test-Path "$toolsRoot/gradle-8.9/bin/gradle.bat")) {
    Get-Archive 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' 'gradle.zip' $toolsRoot
}

$sdkmanager = "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat"
# Installing the requested Android SDK requires accepting its tool licenses.
if (!(Test-Path "$env:ANDROID_HOME/licenses/android-sdk-license")) {
    1..30 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$env:ANDROID_HOME" --licenses > "$toolsRoot/sdk-licenses.log"
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK license step failed' }
}
# Pinned stable archives and SHA-1 checksums from Google's repository2-1.xml.
# Direct range downloads avoid slow single-connection sdkmanager transfers.
function Install-SdkPackage($archiveName, $relativeTarget, $sha1) {
    $target = Join-Path $env:ANDROID_HOME $relativeTarget
    if (Test-Path "$target/source.properties") { return }
    $stage = Join-Path $toolsRoot "sdk-packages/$archiveName"
    Get-Archive "https://dl.google.com/android/repository/$archiveName" $archiveName $stage $sha1
    $root = Get-ChildItem -LiteralPath $stage -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    if (!(Test-Path -LiteralPath $target)) {
        Move-Item -LiteralPath $root.FullName -Destination $target
    } else {
        Get-ChildItem -LiteralPath $root.FullName -Force | Copy-Item -Destination $target -Recurse -Force
    }
}
Install-SdkPackage 'build-tools_r34-windows.zip' 'build-tools/34.0.0' '62cfde1b6fcc3ad12a4d2ba1b537e752768bfd47'
Install-SdkPackage 'platform-35_r02.zip' 'platforms/android-35' '0bb560a90a7a2cbd0dd8348224d518b638fe7949'
Install-SdkPackage 'platform-tools_r37.0.1-win.zip' 'platform-tools' 'e03e78b1d80b396f1c3358e31251cb31740e1110'
& $sdkmanager "--sdk_root=$env:ANDROID_HOME" --list_installed
if ($LASTEXITCODE -ne 0) { throw 'Android SDK verification failed' }
$sdkPath = $env:ANDROID_HOME.Replace('\', '/')
Set-Content -LiteralPath "$projectRoot/android/local.properties" -Value "sdk.dir=$sdkPath" -Encoding ASCII
if (!(Test-Path "$projectRoot/android/gradlew.bat")) {
    & "$toolsRoot/gradle-8.9/bin/gradle.bat" -p "$projectRoot/android" --no-daemon wrapper --gradle-version 8.9 --distribution-type bin --no-validate-url
    if ($LASTEXITCODE -ne 0) { throw 'Gradle wrapper creation failed' }
}
Write-Host 'Android tools installed. Run npm run build:android next.'
