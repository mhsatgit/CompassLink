#requires -Version 7.6
param([switch]$Verify, [switch]$InstallDriver)

$ErrorActionPreference = 'Stop'
if ($InstallDriver -and !$Verify) { throw '-InstallDriver requires -Verify.' }
if ($Verify) {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        $principal = [System.Security.Principal.WindowsPrincipal]::new($identity)
        if (!$principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
            throw 'Run -Verify in an administrator PowerShell 7 session.'
        }
    } finally { $identity.Dispose() }
}

$root = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $root '.tools/gamepad'
$archive = Join-Path $cache 'HIDMaestro-v2.3.0.zip'
$archiveHash = 'fddb61b76d076aa2cf508a46c1e30e23aa75b4ae622d52b15e7259959fd12295'
[void][System.IO.Directory]::CreateDirectory($cache)
if (!(Test-Path -LiteralPath $archive)) {
    $url = 'https://github.com/hifihedgehog/HIDMaestro/releases/download/v2.3.0/HIDMaestro-v2.3.0.zip'
    & node (Join-Path $PSScriptRoot 'download.cjs') $url $archive
    if ($LASTEXITCODE -ne 0) { throw 'HIDMaestro SDK download failed.' }
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $archiveHash) {
    throw "HIDMaestro archive checksum mismatch. Replace $archive with the pinned upstream archive."
}

# Load only the SDK bytes in the integrity-checked archive, even when a cache file already exists.
$zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $entry = $zip.GetEntry('HIDMaestro.Core.dll')
    if (!$entry) { throw 'The archive does not contain the expected HIDMaestro SDK.' }
    $sdk = Join-Path $cache 'HIDMaestro.Core.dll'
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $sdk, $true)
} finally { $zip.Dispose() }

Add-Type -LiteralPath $sdk
$references = @(Get-ChildItem -LiteralPath (Join-Path $PSHOME 'ref') -Filter '*.dll' -File |
    Select-Object -ExpandProperty FullName)
$probeSources = @('pc/windows/gamepad-probe/Probe.cs', 'pc/windows/gamepad-probe/XInput.cs') |
    ForEach-Object { Join-Path $root $_ }
Add-Type -LiteralPath $probeSources -ReferencedAssemblies ($references + $sdk)

$report = Join-Path $cache $(if ($Verify) { 'xinput-probe.txt' } else { 'backend-status.txt' })
$writer = [System.IO.StreamWriter]::new($report)
try {
    [CompassLink.GamepadProbe]::Run($writer, $Verify.IsPresent, $InstallDriver.IsPresent)
} catch {
    $writer.WriteLine($_.Exception.GetBaseException().Message)
    throw
} finally {
    $writer.Dispose()
    Get-Content -LiteralPath $report
}
