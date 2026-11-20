const { test } = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");

test("PowerShell scripts parse and toolchain overrides external installations", {
  skip: process.platform !== "win32",
}, () => {
  const result = spawnSync("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", `
    $ErrorActionPreference = 'Stop'
    Get-ChildItem scripts/*.ps1 | ForEach-Object {
      $tokens = $null; $errors = $null
      [void][Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$tokens, [ref]$errors)
      if ($errors.Count) { throw ($errors | Out-String) }
    }
    . ./scripts/toolchain.ps1
    [void](Get-Command Get-FileHash -ErrorAction Stop)
    Initialize-AndroidEnvironment
    if ((Test-Path Env:ANDROID_SDK_HOME) -or (Test-Path Env:ANDROID_PREFS_ROOT)) {
      throw 'Deprecated preference-location variables were not cleared.'
    }
    $gradle = Get-GradleDistribution
    @{
      java = $env:JAVA_HOME
      sdk = $env:ANDROID_HOME
      sdkRoot = $env:ANDROID_SDK_ROOT
      cache = $env:GRADLE_USER_HOME
      androidUser = $env:ANDROID_USER_HOME
      gradle = $gradle.Executable
    } | ConvertTo-Json -Compress
  `], {
    cwd: path.join(__dirname, ".."),
    encoding: "utf8",
    windowsHide: true,
    env: {
      ...process.env,
      JAVA_HOME: "C:\\external-java",
      ANDROID_HOME: "C:\\external-sdk",
      ANDROID_SDK_ROOT: "C:\\other-sdk",
      GRADLE_USER_HOME: "C:\\external-cache",
      ANDROID_USER_HOME: "C:\\external-preferences",
      ANDROID_SDK_HOME: "C:\\conflicting-legacy-parent",
      ANDROID_PREFS_ROOT: "C:\\another-conflicting-parent",
    },
  });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  const locations = JSON.parse(result.stdout);
  const tools = path.join(__dirname, "..", ".tools");
  for (const [name, location] of Object.entries(locations)) {
    assert.ok(location.startsWith(tools + path.sep), `${name} escaped .tools`);
  }
  assert.equal(locations.sdkRoot, locations.sdk);
});
