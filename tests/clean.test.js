const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

test("clean build removes generated caches and output while retaining tools, dependencies and signing keys", {
  skip: process.platform !== "win32",
}, (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "compasslink-clean-test-"));
  t.after(() => {
    const relative = path.relative(os.tmpdir(), root);
    assert.ok(relative.startsWith("compasslink-clean-test-") && !relative.includes(path.sep));
    fs.rmSync(root, { recursive: true, force: true });
  });
  function write(relative, content = "fixture") {
    const file = path.join(root, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content);
  }
  for (const file of ["scripts/clean.ps1", "scripts/toolchain.ps1", "android/gradle/wrapper/gradle-wrapper.properties"]) {
    write(file, fs.readFileSync(path.join(__dirname, "..", file)));
  }
  const generated = [
    "android/.gradle/state", "android/.kotlin/state", "android/build/report.txt",
    "android/app/build/outputs/apk/debug/app-debug.apk", "android/app/build/intermediates/state",
    ".tools/gradle-cache/caches/9.9.1/kotlin-dsl/state",
    ".tools/gradle-cache/caches/jars-9/generated.jar",
    ".tools/gradle-cache/caches/build-cache-1/state",
    ".tools/gradle-cache/daemon/9.9.1/log", ".tools/gradle-cache/.tmp/state",
  ];
  const retained = [
    "android/app/src/main/source.kt", ".tools/jdk/bin/java.exe",
    ".tools/android-sdk/platforms/android-37.0/android.jar",
    ".tools/android-user/debug.keystore", ".tools/gradle-9.9.1/lib/gradle.jar",
    ".tools/gradle-cache/caches/modules-2/files-2.1/downloaded.jar",
  ];
  for (const file of [...generated, ...retained]) write(file);
  const result = spawnSync("powershell.exe", [
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
    path.join(root, "scripts/clean.ps1"), "-BuildCaches",
  ], { cwd: root, encoding: "utf8", windowsHide: true });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  for (const file of generated) assert.equal(fs.existsSync(path.join(root, file)), false, file);
  for (const file of retained) assert.equal(fs.readFileSync(path.join(root, file), "utf8"), "fixture", file);
});
