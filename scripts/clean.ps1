$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$projectPrefix = $projectRoot.TrimEnd('\') + '\'

function Get-ProjectSize {
    return (Get-ChildItem -LiteralPath $projectRoot -Recurse -Force -File -ErrorAction Stop |
        Measure-Object -Property Length -Sum).Sum
}

$before = Get-ProjectSize
$gradle = Join-Path $projectRoot '.tools/gradle-8.9/bin/gradle.bat'
if (Test-Path -LiteralPath $gradle) {
    $savedJava = $env:JAVA_HOME
    $savedCache = $env:GRADLE_USER_HOME
    try {
        $env:JAVA_HOME = Join-Path $projectRoot '.tools/jdk'
        $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-cache'
        & $gradle --stop
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop project Gradle daemons safely' }
    } finally {
        $env:JAVA_HOME = $savedJava
        $env:GRADLE_USER_HOME = $savedCache
    }
}

# Keep installed tools, cached dependency downloads, source, lockfiles and APK outputs.
$targets = @(
    'work',
    '.tools/java-extract',
    '.tools/sdk-extract',
    '.tools/sdk-packages',
    '.tools/sdk-licenses.log',
    '.tools/android-sdk/.temp',
    '.tools/android-sdk/build-tools/34.0.0/.installer',
    '.tools/gradle-cache/.tmp',
    '.tools/gradle-cache/caches/8.9',
    '.tools/gradle-cache/caches/jars-9',
    '.tools/gradle-cache/caches/journal-1',
    '.tools/gradle-cache/daemon',
    '.tools/gradle-cache/kotlin-profile',
    '.tools/gradle-cache/android',
    'android/.gradle',
    'android/.kotlin',
    'android/app/build/generated',
    'android/app/build/intermediates',
    'android/app/build/kotlin',
    'android/app/build/tmp',
    'android/app/build/reports'
)
$archives = @(
    'jdk.zip', 'android-commandline.zip', 'gradle.zip',
    'build-tools_r34-windows.zip', 'platform-35_r02.zip',
    'platform-tools_r37.0.1-win.zip'
)
foreach ($archive in $archives) {
    $targets += ".tools/$archive"
    $targets += ".tools/$archive.partial"
    $targets += ".tools/$archive.partial.parts"
}

foreach ($relativePath in $targets) {
    $target = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $relativePath))
    if (!$target.StartsWith($projectPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Cleanup target escaped the project: $target"
    }
    if (!(Test-Path -LiteralPath $target)) { continue }
    # Never follow a junction or symbolic link out of the workspace during cleanup.
    $ancestor = $target
    while ($ancestor -ne $projectRoot) {
        $entry = Get-Item -LiteralPath $ancestor -Force
        if ($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            throw "Refusing to clean a linked path: $ancestor"
        }
        $ancestor = Split-Path $ancestor -Parent
    }
    $linkedChildren = @(Get-ChildItem -LiteralPath $target -Force -Recurse -ErrorAction Stop |
        Where-Object { $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint })
    if ($linkedChildren.Count -gt 0) { throw "Refusing to clean a directory containing links: $target" }
    Remove-Item -LiteralPath $target -Recurse -Force
    Write-Host "Removed $relativePath"
}

$after = Get-ProjectSize
Write-Host ('Before: {0:N2} GiB' -f ($before / 1GB))
Write-Host ('After:  {0:N2} GiB' -f ($after / 1GB))
Write-Host ('Freed:  {0:N2} GiB' -f (($before - $after) / 1GB))
Write-Host 'APK and installed build/runtime tools retained. The next Android build will regenerate its caches.'
