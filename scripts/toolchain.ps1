# All helpers use this clone's tools, regardless of global environment settings.
# npm can inherit PowerShell 7's module path before launching Windows PowerShell.
# Prefer this process's built-in modules (including the Get-FileHash function).
$env:PSModulePath = "$PSHOME\Modules;$env:PSModulePath"
$compassProjectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$compassToolsRoot = Join-Path $compassProjectRoot '.tools'

function Resolve-AndroidSdk { return Join-Path $compassToolsRoot 'android-sdk' }

function Resolve-AndroidCli {
    $candidate = Join-Path (Resolve-AndroidSdk) 'cmdline-tools/latest/bin/android.exe'
    if (!(Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw 'Repository-local Android CLI missing. Run npm run setup:android to download the current command-line tools.'
    }
    return $candidate
}

function Get-AndroidSdkRequirements {
    # Android CLI uses slash-separated package identifiers, not sdkmanager's
    # semicolon-separated arguments. Keep these aligned with app/build.gradle.kts.
    return @(
        @{
            Package = 'platforms/android-37.0'
            Files = @('platforms/android-37.0/android.jar', 'platforms/android-37.0/source.properties')
        },
        @{
            Package = 'build-tools/37.0.0'
            Files = @(
                'build-tools/37.0.0/aapt2.exe', 'build-tools/37.0.0/zipalign.exe',
                'build-tools/37.0.0/apksigner.bat', 'build-tools/37.0.0/lib/apksigner.jar',
                'build-tools/37.0.0/source.properties'
            )
        }
    )
}

function Test-AndroidSdkRequirement($Requirement) {
    foreach ($relative in $Requirement.Files) {
        $file = Join-Path (Resolve-AndroidSdk) $relative
        if (!(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).Length -eq 0) {
            return $false
        }
    }
    return $true
}

function Get-GradleDistribution {
    $properties = ConvertFrom-StringData (Get-Content -LiteralPath "$compassProjectRoot/android/gradle/wrapper/gradle-wrapper.properties" -Raw)
    $url = $properties.distributionUrl.Replace('\:', ':')
    if ($url -notmatch '^https://downloads\.gradle\.org/distributions/(gradle-(\d+\.\d+(?:\.\d+)?))-bin\.zip$' -or
        $properties.distributionSha256Sum -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'Expected a pinned official Gradle distribution and SHA-256 in gradle-wrapper.properties.'
    }
    # Read the install directory from the same version pin as the wrapper.
    $folder = [IO.Path]::GetFileName($url) -replace '-bin\.zip$', ''
    return @{
        Url = $url
        Name = [IO.Path]::GetFileName($url)
        Checksum = $properties.distributionSha256Sum
        Executable = Join-Path $compassToolsRoot "$folder/bin/gradle.bat"
    }
}

function Resolve-Adb {
    $candidate = Join-Path (Resolve-AndroidSdk) 'platform-tools/adb.exe'
    if (!(Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw 'Repository-local ADB missing. Run npm run setup:adb first.'
    }
    return $candidate
}

function Initialize-AndroidEnvironment {
    $env:JAVA_HOME = Join-Path $compassToolsRoot 'jdk'
    $env:ANDROID_HOME = Resolve-AndroidSdk
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:GRADLE_USER_HOME = Join-Path $compassToolsRoot 'gradle-cache'
    $env:ANDROID_USER_HOME = Join-Path $compassToolsRoot 'android-user'
    # Legacy aliases name the PARENT of .android; ANDROID_USER_HOME names the
    # directory itself. AGP rejects conflicting values, including inherited ones.
    # Only change this child process's environment, never Windows user settings.
    Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
    Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
    $env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:ANDROID_HOME/cmdline-tools/latest/bin;$env:PATH"
}

function Assert-LocalJdk {
    $release = Join-Path $env:JAVA_HOME 'release'
    if (!(Test-Path "$env:JAVA_HOME/bin/java.exe") -or !(Test-Path "$env:JAVA_HOME/bin/javac.exe") -or
        !(Test-Path $release) -or !(Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="25[."]' -Quiet)) {
        throw 'Repository-local JDK 25 missing or incompatible. Run npm run setup:android first.'
    }
}

function Initialize-AndroidBuild {
    Initialize-AndroidEnvironment
    # Replace a stale SDK path from an older checkout even if setup is incomplete.
    $sdk = $env:ANDROID_HOME
    Set-Content -LiteralPath "$compassProjectRoot/android/local.properties" -Value ('sdk.dir=' + $sdk.Replace('\', '/')) -Encoding ASCII
    Assert-LocalJdk
    foreach ($requirement in (Get-AndroidSdkRequirements)) {
        if (!(Test-AndroidSdkRequirement $requirement)) {
            throw "Repository-local SDK package missing or incomplete: $($requirement.Package). Run npm run setup:android."
        }
    }
    $null = Resolve-Adb
    $null = Resolve-AndroidCli
    if (!(Test-Path -LiteralPath (Get-GradleDistribution).Executable -PathType Leaf)) {
        throw 'Repository-local Gradle missing. Run npm run setup:android first.'
    }
}
