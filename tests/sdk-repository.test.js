const { test } = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");

function feed({ checksum = "a".repeat(40), attributes = "", prefix = "", host = "windows", name = "commandlinetools-win-123_latest.zip", packagePath = "cmdline-tools;latest" } = {}) {
  const tag = (name) => prefix + name;
  const namespace = prefix ? 'xmlns:sdk="urn:android-sdk-test"' : 'xmlns="urn:android-sdk-test"';
  return `<${tag("sdk-repository")} ${namespace}>
    <${tag("remotePackage")} path="${packagePath}">
      <${tag("archives")}>
        <${tag("archive")}><${tag("host-os")}>linux</${tag("host-os")}></${tag("archive")}>
        <${tag("archive")}>
          <${tag("host-os")}>${host}</${tag("host-os")}>
          <${tag("complete")}>
            <${tag("url")}>${name}</${tag("url")}>
            <${tag("checksum")} ${attributes}>${checksum}</${tag("checksum")}>
          </${tag("complete")}>
        </${tag("archive")}>
      </${tag("archives")}>
    </${tag("remotePackage")}>
  </${tag("sdk-repository")}>`;
}

test("Google SDK feed parser handles plain checksums, attributes and XML namespaces", {
  skip: process.platform !== "win32",
}, () => {
  const fixtures = [
    { xml: feed(), algorithm: "SHA1" },
    { xml: feed({ attributes: 'type="sha1"' }), algorithm: "SHA1" },
    { xml: feed({ prefix: "sdk:" }), algorithm: "SHA1" },
    { xml: feed({ attributes: 'type="sha-256"', checksum: "b".repeat(64) }), algorithm: "SHA256" },
    { xml: feed({ host: "macosx" }), error: true },
    { xml: feed({ packagePath: "platform-tools" }), error: true },
    { xml: feed({ checksum: "invalid" }), error: true },
    { xml: feed({ name: "../untrusted.zip" }), error: true },
    { xml: feed({ attributes: 'type="md5"' }), error: true },
  ];
  const result = spawnSync("powershell.exe", [
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", `
      $ErrorActionPreference = 'Stop'
      . ./scripts/toolchain.ps1
      . ./scripts/sdk-repository.ps1
      $fixtures = [Console]::In.ReadToEnd() | ConvertFrom-Json
      $results = @(foreach ($fixture in $fixtures) {
        try { Get-AndroidCommandLineArchive ([xml]$fixture.xml) }
        catch { @{ Error = $_.Exception.Message } }
      })
      ConvertTo-Json -InputObject $results -Depth 4 -Compress
    `,
  ], {
    cwd: path.join(__dirname, ".."),
    input: JSON.stringify(fixtures),
    encoding: "utf8",
    windowsHide: true,
  });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  const results = JSON.parse(result.stdout);
  assert.equal(results.length, fixtures.length);
  fixtures.forEach((fixture, i) => {
    if (fixture.error) {
      assert.ok(results[i].Error, `Fixture ${i} should fail validation`);
    } else {
      assert.equal(results[i].Algorithm, fixture.algorithm);
      assert.equal(results[i].Name, "commandlinetools-win-123_latest.zip");
      assert.equal(results[i].Url, "https://dl.google.com/android/repository/commandlinetools-win-123_latest.zip");
      assert.equal(results[i].Checksum.length, fixture.algorithm === "SHA1" ? 40 : 64);
    }
  });
});
