param([string]$Serial)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/toolchain.ps1"
$adb = Resolve-Adb
$device = if ($Serial) { @('-s', $Serial) } else { @('-d') }
$dump = '/data/local/tmp/compasslink-navigation.xml'

function Read-Screen {
    $result = & $adb @device shell uiautomator dump $dump 2>&1
    if ($LASTEXITCODE -ne 0 -or "$result" -notmatch 'dumped to') {
        throw "Cannot read a stable screen. $result"
    }
    [xml]$xml = (& $adb @device shell cat $dump) -join [Environment]::NewLine
    return $xml.SelectNodes('//node[@package="com.compasslink3d"]')
}

function Find-Action([string]$Label) {
    $nodes = Read-Screen
    $found = $nodes | Where-Object { $_.text -eq $Label -or $_.'content-desc' -eq $Label } | Select-Object -Last 1
    if (!$found) { throw "Expected '$Label'. Visible labels: $(($nodes.text | Where-Object { $_ }) -join ', ')" }
    return $found
}

function Tap([string]$Label) {
    $bounds = (Find-Action $Label).bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Invalid bounds for $Label" }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $adb @device shell input tap $x $y
    if ($LASTEXITCODE -ne 0) { throw "Tap failed for $Label" }
    Start-Sleep -Milliseconds 450
}

function Back {
    & $adb @device shell input keyevent 4
    Start-Sleep -Milliseconds 450
}

# Start on Connect with the phone unlocked. Only the new, unsaved draft is discarded.
[void](Find-Action 'Check link')
Tap 'Controllers'
Tap 'Play'
[void](Find-Action 'Pause')
Back
Tap 'Resume Play'
& $adb @device shell input keyevent 111
Tap 'End session'
Tap 'New profile'
Tap 'Create'
Tap 'Add control'
Tap 'Button'
Tap 'Open editor tools'
Tap 'Control properties'
Tap 'Position and size'
Back
[void](Find-Action 'Input bindings')
Back
Tap 'Resume editing'
Back
Tap 'Keep editing'
Back
Tap 'Discard'
[void](Find-Action 'New profile')
Back
[void](Find-Action 'Check link')
Write-Output 'Passed Play, Back, Escape, Resume, editor, nested form, dirty draft and return-to-Connect checks.'
