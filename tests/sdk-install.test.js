const { test } = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");

function powershell(code) {
  const result = spawnSync(
    "powershell.exe",
    [
      "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
      `$ErrorActionPreference = 'Stop'
       . ./scripts/toolchain.ps1
       . ./scripts/install-sdk.ps1
       function Write-Host { }
       ${code}`,
    ],
    { cwd: path.join(__dirname, ".."), encoding: "utf8", windowsHide: true },
  );
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  return JSON.parse(result.stdout);
}

test("SDK installer keeps package identifiers intact and skips completed packages", {
  skip: process.platform !== "win32",
}, () => {
  const result = powershell(`
    # Use an isolated fixture SDK and a fake CLI; never download during this test.
    $compassToolsRoot = Join-Path ([IO.Path]::GetTempPath()) ('compasslink-sdk-test-' + [guid]::NewGuid().ToString('N'))
    $env:ANDROID_HOME = Resolve-AndroidSdk
    $script:calls = [Collections.Generic.List[string]]::new()
    function Invoke-AndroidCli([string[]]$Arguments) {
      if ($Arguments.Count -ne 3 -or $Arguments[0] -ne 'sdk' -or $Arguments[1] -ne 'install' -or
          $Arguments[2] -notin @('platforms/android-37.0', 'build-tools/37.0.0')) {
        throw 'SDK package name or install command was split incorrectly.'
      }
      $script:calls.Add($Arguments[2])
      $requirement = Get-AndroidSdkRequirements | Where-Object { $_.Package -eq $Arguments[2] }
      foreach ($relative in $requirement.Files) {
        $file = Join-Path $env:ANDROID_HOME $relative
        New-Item -ItemType Directory -Path (Split-Path $file -Parent) -Force | Out-Null
        [IO.File]::WriteAllText($file, 'installed fixture')
      }
    }
    try {
      Install-RequiredAndroidSdkPackages
      Install-RequiredAndroidSdkPackages
      $before = $script:calls.Count
      [IO.File]::WriteAllText((Join-Path $env:ANDROID_HOME 'build-tools/37.0.0/aapt2.exe'), '')
      Install-RequiredAndroidSdkPackages
      @{ Calls = @($script:calls.ToArray()); BeforeRepair = $before } | ConvertTo-Json -Compress
    } finally {
      # Verify the resolved fixture target before removing generated test files.
      $target = [IO.Path]::GetFullPath($compassToolsRoot)
      $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\\') + '\\'
      if (!$target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
          (Split-Path $target -Leaf) -notlike 'compasslink-sdk-test-*') { throw 'Unsafe fixture cleanup path' }
      Remove-Item -LiteralPath $target -Recurse -Force
    }
  `);
  assert.deepEqual(result.Calls, [
    "platforms/android-37.0", "build-tools/37.0.0", "build-tools/37.0.0",
  ]);
  assert.equal(result.BeforeRepair, 2);
});

test("SDK installer rejects a zero exit result when packages are still missing", {
  skip: process.platform !== "win32",
}, () => {
  const result = powershell(`
    $compassToolsRoot = Join-Path ([IO.Path]::GetTempPath()) ('compasslink-sdk-absent-' + [guid]::NewGuid().ToString('N'))
    $env:ANDROID_HOME = Resolve-AndroidSdk
    function Invoke-AndroidCli([string[]]$Arguments) { $global:LASTEXITCODE = 0 }
    $failure = $null
    try { Install-RequiredAndroidSdkPackages }
    catch { $failure = $_.Exception.Message }
    @{ Failure = $failure } | ConvertTo-Json -Compress
  `);
  assert.match(result.Failure, /did not install all files for platforms\/android-37\.0/);
});

test("Android CLI invocation quotes an SDK path with spaces and propagates failure", {
  skip: process.platform !== "win32",
}, () => {
  const result = powershell(`
    Initialize-AndroidEnvironment
    $env:ANDROID_HOME = 'C:\\fixture sdk with spaces'
    $script:received = $null
    function FakeAndroid {
      $script:received = @($args)
      $global:LASTEXITCODE = 27
    }
    function Resolve-AndroidCli { return 'FakeAndroid' }
    $failure = $null
    try { Invoke-AndroidCli -Arguments @('sdk', 'install', 'platforms/android-37.0') }
    catch { $failure = $_.Exception.Message }
    @{ Arguments = $script:received; Failure = $failure } | ConvertTo-Json -Compress
  `);
  assert.deepEqual(result.Arguments, [
    "--no-metrics", "--sdk=C:\\fixture sdk with spaces", "sdk", "install", "platforms/android-37.0",
  ]);
  assert.match(result.Failure, /exit 27/);
});
