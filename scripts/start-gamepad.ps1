#requires -Version 5.1
param([switch]$BuildOnly, [switch]$Foreground)

$ErrorActionPreference = 'Stop'
$env:PSModulePath = "$PSHOME\Modules;$env:PSModulePath"
if ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'The CompassLink gamepad host requires x64 Windows.'
}
$root = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $root '.tools/gamepad'
[void][IO.Directory]::CreateDirectory($cache)

function Get-PinnedArchive([string]$Name, [string]$Url, [string]$Algorithm, [string]$Digest) {
    $file = Join-Path $cache $Name
    if (!(Test-Path -LiteralPath $file)) {
        & node (Join-Path $PSScriptRoot 'download.cjs') $Url $file | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Could not download $Name." }
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm $Algorithm).Hash -ine $Digest) {
        throw "Checksum mismatch for $file. Replace it with the pinned upstream archive."
    }
    return $file
}

$sdk = Join-Path $cache 'HIDMaestro.Core.dll'
$sdkDigest = '9a2e32ccc4cc186098c47073e10396003b6a12dbd479acc351d9576f568135a8'
$license = Join-Path $cache 'HIDMaestro-LICENSE.txt'
$notices = Join-Path $cache 'HIDMaestro-THIRD-PARTY-NOTICES.txt'
if (!(Test-Path -LiteralPath $sdk) -or (Get-FileHash -LiteralPath $sdk -Algorithm SHA256).Hash -ine $sdkDigest -or
    !(Test-Path -LiteralPath $license) -or !(Test-Path -LiteralPath $notices)) {
    $hidArchive = Get-PinnedArchive 'HIDMaestro-v2.3.0.zip' `
        'https://github.com/hifihedgehog/HIDMaestro/releases/download/v2.3.0/HIDMaestro-v2.3.0.zip' `
        'SHA256' 'fddb61b76d076aa2cf508a46c1e30e23aa75b4ae622d52b15e7259959fd12295'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($hidArchive)
    try {
        $entry = $zip.GetEntry('HIDMaestro.Core.dll')
        if (!$entry) { throw 'HIDMaestro.Core.dll is missing from the pinned archive.' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $sdk, $true)
        [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('LICENSE'), $license, $true)
        [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('THIRD-PARTY-NOTICES.txt'), $notices, $true)
    } finally { $zip.Dispose() }
    if ((Get-FileHash -LiteralPath $sdk -Algorithm SHA256).Hash -ine $sdkDigest) { throw 'Invalid HIDMaestro SDK.' }
}
$tools = Join-Path $cache 'dotnet'
$dotnet = Join-Path $tools 'dotnet.exe'
if (!(Test-Path -LiteralPath (Join-Path $tools 'sdk/10.0.404/dotnet.dll'))) {
    $dotnetArchive = Get-PinnedArchive 'dotnet-sdk-10.0.404-win-x64.zip' `
        'https://dotnetcli.blob.core.windows.net/dotnet/Sdk/10.0.404/dotnet-sdk-10.0.404-win-x64.zip' `
        'SHA512' '330ddd2665b43cd2e428216a0d8ea1ac7bb19b5434fdc530e62010dc3b52823a620bf95489d628e1ed6f5122401793ee4887cca4a019122361a1231497f632ee'
    Expand-Archive -LiteralPath $dotnetArchive -DestinationPath $tools -Force
}

$env:DOTNET_CLI_HOME = $cache
$env:DOTNET_NOLOGO = '1'
$env:DOTNET_ADD_GLOBAL_TOOLS_TO_PATH = '0'
$build = Join-Path $cache 'gamepad-host'
& $dotnet build (Join-Path $root 'pc/windows/gamepad-host/GamepadHost.csproj') -c Release `
    --artifacts-path (Join-Path $cache 'build') --output $build --nologo `
    "-p:RestoreConfigFile=$root/pc/windows/gamepad-host/NuGet.Config"
if ($LASTEXITCODE -ne 0) { throw 'Gamepad host build failed.' }
Copy-Item -LiteralPath $license, $notices -Destination $build
$assembly = Join-Path $build 'CompassLink.GamepadHost.dll'
if ($BuildOnly) { return }

if ($Foreground) {
    & $dotnet $assembly
    if ($LASTEXITCODE -ne 0) { throw "Gamepad host exited with $LASTEXITCODE." }
} else {
    $process = Start-Process -FilePath $dotnet -ArgumentList ('"' + $assembly + '"') -Verb RunAs -WindowStyle Hidden -PassThru
    Write-Output "Started the CompassLink USB input helper (PID $($process.Id)). Open a keyboard or gamepad profile on the phone."
}
