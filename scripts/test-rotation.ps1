param([string]$Serial)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/toolchain.ps1"
$adb = Resolve-Adb
$selector = if ($Serial) { @('-s', $Serial) } else { @('-d') }
$dump = '/data/local/tmp/compasslink-rotation-test.xml'

function Invoke-Phone([string[]]$Arguments) {
    $result = & $adb @selector shell @Arguments
    if ($LASTEXITCODE) { throw "ADB failed for $Arguments" }
    return $result
}

function Read-Layout {
    $result = Invoke-Phone @('uiautomator', 'dump', $dump)
    if ("$result" -notmatch 'dumped to') { throw "Could not capture the current layout: $result" }
    return [xml]((Invoke-Phone @('cat', $dump)) -join [Environment]::NewLine)
}

function Find-Visible($Layout, [string]$Label, [bool]$Clickable = $false) {
    $candidates = $Layout.SelectNodes("//node[@package='com.compasslink3d']") | Where-Object {
        $_.text -eq $Label -and (!$Clickable -or $_.clickable -eq 'true')
    }
    foreach ($node in $candidates) {
        if ($node.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
            $box = [int[]]@($Matches[1], $Matches[2], $Matches[3], $Matches[4])
            if ($box[2] -gt $box[0] -and $box[3] -gt $box[1]) { return ,$box }
        }
    }
    throw "Missing visible control '$Label'"
}

function Open-Section([string]$Label) {
    $box = Find-Visible (Read-Layout) $Label $true
    Invoke-Phone @('input', 'tap', [string][int](($box[0]+$box[2])/2), [string][int](($box[1]+$box[3])/2))
}

# Exercise compact portrait and wide landscape on a phone. Start on Connect.
$original = (Invoke-Phone @('wm', 'user-rotation')).Trim()
$angle = (Invoke-Phone @('settings', 'get', 'system', 'user_rotation')).Trim()
if ($original -notmatch '^(free|lock [0-3])$' -or $angle -notmatch '^[0-3]$') {
    throw "Cannot determine the phone's original rotation configuration"
}
try {
    $sections = [ordered]@{Connect='CompassLink'; Controllers='New profile'; Sensors='Quaternion details'}
    foreach ($section in $sections.Keys) {
        Open-Section $section
        foreach ($turn in @(0, 1, 0, 3, 0)) {
            Invoke-Phone @('wm', 'user-rotation', 'lock', "$turn")
            Start-Sleep -Milliseconds 700
            $layout = Read-Layout
            if ([int]$layout.hierarchy.rotation -ne $turn) { throw "$section did not rotate to $turn" }
            $first = Find-Visible $layout Connect $true
            $last = Find-Visible $layout Sensors $true
            $body = Find-Visible $layout $sections[$section]
            if ($turn -eq 0) {
                if ($first[1] -ne $last[1] -or $body[3] -gt $first[1]) { throw "$section portrait alignment failed" }
            } elseif ($first[0] -ne $last[0] -or $body[0] -lt $first[2]) {
                throw "$section landscape alignment failed"
            }
            Write-Output "$section rotation $turn passed"
        }
    }
    Open-Section Connect
} finally {
    Invoke-Phone @('wm', 'user-rotation', 'lock', $angle)
    if ($original -eq 'free') { Invoke-Phone @('wm', 'user-rotation', 'free') }
}
