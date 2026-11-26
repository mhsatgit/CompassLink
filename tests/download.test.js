const { test } = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const vm = require("node:vm");

const url = "https://example.test/archive.zip";

async function fixture(
  t,
  { rejectHead = false, ignoreRanges = false, fail = false, size = 19 } = {},
) {
  const root = await fs.promises.mkdtemp(
    path.join(os.tmpdir(), "compasslink-download-test-"),
  );
  const target = path.join(root, "archive.partial");
  t.after(async () => {
    await fs.promises.unlink(target).catch((error) => {
      if (error.code !== "ENOENT") throw error;
    });
    await fs.promises.rmdir(root);
  });
  const payload = Buffer.from(Array.from({ length: size }, (_, i) => i));
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
      const output = args[args.indexOf("--output") + 1];
      const range = args.indexOf("--range");
      let content = payload;
      if (range >= 0 && !ignoreRanges) {
        const [start, end] = args[range + 1].split("-").map(Number);
        assert.ok(start <= end && end < payload.length);
        content = payload.subarray(start, end + 1);
      }
      fs.writeFile(output, fail ? content.subarray(0, 1) : content, (error) => {
        if (error) child.emit("error", error);
        else child.emit("close", fail ? 18 : 0);
      });
      return child;
    },
  };
  const context = {
    module: { exports: {} },
    URL,
    require(name) {
      return name === "node:child_process" ? childProcess : require(name);
    },
  };
  vm.runInNewContext(
    fs.readFileSync(path.join(__dirname, "../scripts/download.cjs"), "utf8"),
    context,
  );
  return {
    download: context.module.exports.download,
    target,
    root,
    payload,
    requests,
  };
}

async function verifyArchive({ target, payload, root }) {
  assert.deepEqual(await fs.promises.readFile(target), payload);
  assert.deepEqual(await fs.promises.readdir(root), [path.basename(target)]);
}

test("archive download assembles uneven and small byte ranges exactly", async (t) => {
  for (const size of [1, 7, 9, 19]) {
    const run = await fixture(t, { size });
    await run.download(url, run.target);
    await verifyArchive(run);
    assert.equal(run.requests.length, Math.min(8, size));
  }
});

test("archive download falls back to GET when HEAD is rejected", async (t) => {
  const run = await fixture(t, { rejectHead: true });
  await run.download(url, run.target);
  await verifyArchive(run);
  assert.equal(run.requests.length, 1);
});

test("archive download handles proxies that ignore byte ranges", async (t) => {
  const run = await fixture(t, { ignoreRanges: true });
  await run.download(url, run.target);
  await verifyArchive(run);
  assert.equal(run.requests.length, 9);
  assert.equal(run.requests.at(-1).includes("--range"), false);
});

test("a failed download preserves the previous archive and removes partial files", async (t) => {
  const run = await fixture(t, { fail: true });
  await fs.promises.writeFile(run.target, run.payload);
  await assert.rejects(run.download(url, run.target), /did not complete/);
  await verifyArchive(run);
  await assert.rejects(
    run.download("http://example.test/archive.zip", run.target),
    /https/,
  );
  assert.ok(run.requests.every((args) => args.includes("--proto-redir")));
});
