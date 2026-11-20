param([Parameter(Mandatory = $true)][string]$OutputPath)

$ErrorActionPreference = 'Stop'
try {
    $source = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'InputEngine.cs'))
    Add-Type -TypeDefinition $source -OutputAssembly $OutputPath -OutputType WindowsApplication
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
