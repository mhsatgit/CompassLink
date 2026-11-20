param([switch]$PlatformToolsOnly)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
. "$PSScriptRoot/toolchain.ps1"
. "$PSScriptRoot/sdk-repository.ps1"
. "$PSScriptRoot/install-sdk.ps1"
Initialize-AndroidEnvironment
New-Item -ItemType Directory -Force -Path $compassToolsRoot | Out-Null
Write-Host "Installing tools inside $compassToolsRoot (external Java, SDK and Gradle installations are not used)."

function Get-Archive([string]$Url, [string]$Name, [string]$Destination, [string]$Checksum, [string]$Algorithm = 'SHA256') {
    $archive = Join-Path $compassToolsRoot $Name
    if (!(Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading $Name"
        & node "$PSScriptRoot/download.cjs" $Url "$archive.partial"
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Name. Check internet/DNS, then rerun setup." }
        if ((Get-FileHash -LiteralPath "$archive.partial" -Algorithm $Algorithm).Hash -ne $Checksum) {
            throw "Downloaded archive checksum mismatch: $archive.partial. The archive was not installed."
        }
        Move-Item -LiteralPath "$archive.partial" -Destination $archive -Force
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -ne $Checksum) {
        throw "Archive checksum mismatch: $archive. Remove that archive and rerun setup."
    }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    if (Get-Command tar.exe -ErrorAction SilentlyContinue) {
        & tar.exe -xf $archive -C $Destination
        if ($LASTEXITCODE -ne 0) { throw "Archive extraction failed: $Name" }
    } else {
        Expand-Archive -LiteralPath $archive -DestinationPath $Destination -Force
    }
}

if (!(Test-Path "$env:ANDROID_HOME/platform-tools/adb.exe")) {
    # Stable Windows package and checksum from Google's SDK repository feed.
    Get-Archive 'https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip' 'platform-tools_r37.0.1-win.zip' $env:ANDROID_HOME 'e03e78b1d80b396f1c3358e31251cb31740e1110' 'SHA1'
}
$adb = Resolve-Adb
& $adb version
if ($LASTEXITCODE -ne 0) { throw 'Local ADB verification failed.' }
if ($PlatformToolsOnly) {
    Write-Host "ADB ready: $adb"
    return
}

if (!(Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
    # Download a pinned JDK; never copy or depend on a PC installation.
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/version/25.0.3%2B9?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = @($assets[0].binaries)[0].package
    if (!$package.link -or !$package.checksum) { throw 'Adoptium did not return the pinned JDK 25.0.3+9 package.' }
    $stage = Join-Path $compassToolsRoot 'java-extract'
    Get-Archive $package.link 'jdk-25.0.3.zip' $stage $package.checksum
    $jdkFolder = Get-ChildItem -LiteralPath $stage -Directory | Where-Object { Test-Path "$($_.FullName)/bin/javac.exe" } | Select-Object -First 1
    if (!$jdkFolder) { throw 'Downloaded JDK archive has no javac.exe.' }
    New-Item -ItemType Directory -Force -Path $env:JAVA_HOME | Out-Null
    Get-ChildItem -LiteralPath $jdkFolder.FullName -Force | Copy-Item -Destination $env:JAVA_HOME -Recurse -Force
}
Assert-LocalJdk

# Download Gradle during setup using the same URL and checksum as the wrapper.
# Builds use this local distribution, so they never repeat the wrapper download.
$gradle = Get-GradleDistribution
if (!(Test-Path -LiteralPath $gradle.Executable -PathType Leaf)) {
    Get-Archive $gradle.Url $gradle.Name $compassToolsRoot $gradle.Checksum
}

if (!(Test-Path "$env:ANDROID_HOME/cmdline-tools/latest/bin/android.exe")) {
    # Google's current stable command-line tools, verified against the published checksum.
    [xml]$feed = (Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/repository2-1.xml').Content
    $archive = Get-AndroidCommandLineArchive $feed
    $stage = Join-Path $compassToolsRoot 'sdk-extract'
    Get-Archive $archive.Url $archive.Name $stage $archive.Checksum $archive.Algorithm
    $destination = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Get-ChildItem -LiteralPath "$stage/cmdline-tools" -Force | Copy-Item -Destination $destination -Recurse -Force
}
# Android CLI manages SDK terms during installation; sdkmanager --licenses is
# deprecated and no longer needed. Its launcher/runtime stay in ANDROID_USER_HOME.
Install-RequiredAndroidSdkPackages
Initialize-AndroidBuild
Write-Host "JDK ready: $env:JAVA_HOME"
Write-Host "Android SDK ready: $env:ANDROID_HOME"
Write-Host "Android CLI ready: $(Resolve-AndroidCli)"
Write-Host "Gradle ready: $($gradle.Executable)"
Write-Host "Gradle cache: $env:GRADLE_USER_HOME"
Write-Host 'Run npm run build:android next. No system environment variables were changed.'
