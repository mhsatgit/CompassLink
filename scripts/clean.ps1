param([switch]$BuildCaches)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$projectPrefix = $projectRoot.TrimEnd('\') + '\'

function Get-ProjectSize {
    return (Get-ChildItem -LiteralPath $projectRoot -Recurse -Force -File -ErrorAction Stop |
        Measure-Object -Property Length -Sum).Sum
}

$before = Get-ProjectSize
# Only remove generated files inside this project. Keep .tools and its build cache.
$targets = @(
    'work',
    'android/.gradle',
    'android/.kotlin',
    'android/app/build/generated',
    'android/app/build/intermediates',
    'android/app/build/kotlin',
    'android/app/build/tmp',
    'android/app/build/reports'
)
if ($BuildCaches) {
    . "$PSScriptRoot/toolchain.ps1"
    Initialize-AndroidEnvironment
    $gradle = (Get-GradleDistribution).Executable
    if (Test-Path -LiteralPath $gradle) {
        & $gradle --stop --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Unable to stop repository-local Gradle daemons. Stop builds before clearing their caches.' }
    }
    $targets = @(
        'work', '.gradle', '.kotlin', 'android/.gradle', 'android/.kotlin',
        'android/build', 'android/app/build', '.tools/gradle-cache/.tmp',
        '.tools/gradle-cache/daemon', '.tools/gradle-cache/native',
        '.tools/gradle-cache/notifications'
    )
    $cacheRoot = Join-Path $projectRoot '.tools/gradle-cache/caches'
    if (Test-Path -LiteralPath $cacheRoot) {
        # Downloaded Maven dependencies are retained; the other caches contain
        # generated scripts, artifact transforms, instrumentation and build state.
        foreach ($entry in (Get-ChildItem -LiteralPath $cacheRoot -Force)) {
            if ($entry.Name -ne 'modules-2') { $targets += '.tools/gradle-cache/caches/' + $entry.Name }
        }
    }
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
if ($BuildCaches) {
    Write-Host 'Generated Android output and Gradle build caches cleared. Downloaded tools, Maven dependencies and signing keys retained.'
} else {
    Write-Host 'APK retained. Repository-local .tools (JDK, SDK, signing key and Gradle cache) was not modified.'
}
