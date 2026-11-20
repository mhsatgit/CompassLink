const { test } = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

// Exercise the downloader with simulated HTTP responses, without network access.
function fixture({ rejectHead = false, ignoreRanges = false, size = 19 } = {}) {
  const payload = Buffer.from(Array.from({ length: size }, (_, i) => i));
  const files = new Map();
  const requests = [];
  const childProcess = {
    execFileSync() {
      if (rejectHead) throw new Error("HEAD rejected");
      return `Content-Length: ${payload.length}\r\nAccept-Ranges: bytes\r\n`;
    },
    spawn(executable, args) {
      assert.equal(executable, "curl.exe");
      requests.push(args);
      const child = new EventEmitter();
      queueMicrotask(() => {
        const output = args[args.indexOf("--output") + 1];
        const range = args.indexOf("--range");
        let content = payload;
        if (range >= 0 && !ignoreRanges) {
          const [start, end] = args[range + 1].split("-").map(Number);
          assert.ok(start <= end && end < payload.length);
          content = payload.subarray(start, end + 1);
        }
        files.set(output, content);
        child.emit("exit", 0);
      });
      return child;
    },
  };
  const fakeFs = {
    mkdir: async () => {},
    stat: async (file) => {
      if (!files.has(file)) throw new Error("ENOENT");
      return { size: files.get(file).length };
    },
    open: async (file) => {
      files.set(file, Buffer.alloc(0));
      return {
        writeFile: async (bytes) => {
          files.set(file, Buffer.concat([files.get(file), bytes]));
        },
        close: async () => {},
      };
    },
    readFile: async (file) => files.get(file),
    unlink: async (file) => files.delete(file),
    rmdir: async () => {},
  };
  const context = {
    module: { exports: {} },
    require(name) {
      if (name === "node:child_process") return childProcess;
      if (name === "node:fs/promises") return fakeFs;
      if (name === "node:path") return path;
      throw new Error(`Unexpected import: ${name}`);
    },
    console: { log() {} },
  };
  vm.runInNewContext(
    fs.readFileSync(path.join(__dirname, "../scripts/download.cjs"), "utf8"),
    context,
  );
  return { download: context.module.exports.download, files, payload, requests };
}

test("archive download assembles uneven and small byte ranges exactly", async () => {
  for (const size of [1, 7, 9, 19]) {
    const run = fixture({ size });
    await run.download("https://example.test/archive.zip", "archive.partial");
    assert.deepEqual(run.files.get("archive.partial"), run.payload);
    assert.equal(run.requests.length, Math.min(8, size));
    assert.equal(run.files.size, 1);
  }
});

test("archive download falls back to GET when HEAD is rejected", async () => {
  const run = fixture({ rejectHead: true });
  await run.download("https://example.test/archive.zip", "archive.partial");
  assert.deepEqual(run.files.get("archive.partial"), run.payload);
  assert.equal(run.requests.length, 1);
});

test("archive download handles proxies that ignore byte ranges", async () => {
  const run = fixture({ ignoreRanges: true });
  await run.download("https://example.test/archive.zip", "archive.partial");
  assert.deepEqual(run.files.get("archive.partial"), run.payload);
  assert.equal(run.requests.length, 9);
  assert.equal(run.requests.at(-1).includes("--range"), false);
  assert.equal(run.files.size, 1);
});
