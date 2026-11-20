function Invoke-AndroidCli([string[]]$Arguments) {
    $android = Resolve-AndroidCli
    # Invoke the native executable directly. The deprecated sdkmanager.bat wrapper
    # can split semicolon-delimited package names when invoked by PowerShell.
    & $android '--no-metrics' "--sdk=$env:ANDROID_HOME" @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Android CLI failed (exit $LASTEXITCODE): android $($Arguments -join ' ')"
    }
}

function Install-RequiredAndroidSdkPackages {
    foreach ($requirement in (Get-AndroidSdkRequirements)) {
        if (Test-AndroidSdkRequirement $requirement) {
            Write-Host "SDK package already installed: $($requirement.Package)"
            continue
        }
        Write-Host "Installing SDK package: $($requirement.Package)"
        Invoke-AndroidCli -Arguments @('sdk', 'install', $requirement.Package)
        # Some CLI failures report success to the shell. Confirm the actual files
        # before proceeding, rather than relying only on the process exit code.
        if (!(Test-AndroidSdkRequirement $requirement)) {
            throw "Android CLI did not install all files for $($requirement.Package). Check the output above. Rerun setup after resolving the reported error."
        }
    }
}
