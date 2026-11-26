const { spawn, execFileSync } = require("node:child_process");
const { createReadStream, createWriteStream } = require("node:fs");
const fs = require("node:fs/promises");
const path = require("node:path");
const { pipeline } = require("node:stream/promises");

const CURL_OPTIONS = [
  "--fail",
  "--location",
  "--silent",
  "--show-error",
  "--proto",
  "=https",
  "--proto-redir",
  "=https",
  "--connect-timeout",
  "20",
  "--speed-limit",
  "1024",
  "--speed-time",
  "45",
];

function curl(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      "curl.exe",
      [...CURL_OPTIONS, "--retry", "2", ...args],
      {
        windowsHide: true,
        stdio: "inherit",
      },
    );
    child.on("error", reject);
    child.on("close", (code) =>
      code === 0
        ? resolve()
        : reject(
            new Error(
              "Archive download did not complete (curl exit " + code + ")",
            ),
          ),
    );
  });
}

function rangeSize(url) {
  try {
    const headers = execFileSync("curl.exe", [...CURL_OPTIONS, "--head", url], {
      encoding: "utf8",
      windowsHide: true,
    })
      .trim()
      .split(/\r?\n\r?\n/)
      .at(-1);
    const bytes = Number(headers.match(/^Content-Length:[ \t]*(\d+)/im)?.[1]);
    if (
      Number.isSafeInteger(bytes) &&
      bytes > 0 &&
      /^Accept-Ranges:[ \t]*bytes/im.test(headers)
    )
      return bytes;
  } catch {
    // A host can reject HEAD and still support a regular GET.
  }
  return 0;
}

async function download(url, target) {
  let address;
  try {
    address = new URL(url);
  } catch {
    /* Checked below. */
  }
  if (
    !target ||
    address?.protocol !== "https:" ||
    address.username ||
    address.password
  )
    throw new Error("Usage: node scripts/download.cjs https://URL OUTPUT_FILE");

  const destination = path.resolve(target);
  const parent = path.dirname(destination);
  await fs.mkdir(parent, { recursive: true });
  const stage = await fs.mkdtemp(path.join(parent, ".compasslink-download-"));
  const archive = path.join(stage, "archive");
  const size = rangeSize(address.href);
  const count = Math.min(8, size);
  const parts = Array.from({ length: count }, (_, index) =>
    path.join(stage, String(index)),
  );

  try {
    const ranges = await Promise.allSettled(
      parts.map(async (file, index) => {
        const start = Math.floor((size * index) / count);
        const end = Math.floor((size * (index + 1)) / count) - 1;
        await curl([
          "--range",
          [start, end].join("-"),
          "--output",
          file,
          address.href,
        ]);
        if ((await fs.stat(file)).size !== end - start + 1)
          throw new Error("Unexpected archive range size");
      }),
    );

    if (count && ranges.every((result) => result.status === "fulfilled")) {
      async function* archiveParts() {
        for (const part of parts) yield* createReadStream(part);
      }
      await pipeline(archiveParts(), createWriteStream(archive));
    } else {
      // Wait for the range downloads to settle before a full download.
      await curl(["--output", archive, address.href]);
    }

    const bytes = (await fs.stat(archive)).size;
    if (bytes === 0 || (size && bytes !== size))
      throw new Error("Archive size mismatch");
    await fs.rename(archive, destination);
  } finally {
    for (const file of [...parts, archive]) {
      await fs.unlink(file).catch((error) => {
        if (error.code !== "ENOENT") throw error;
      });
    }
    await fs.rmdir(stage);
  }
}

if (require.main === module) {
  download(...process.argv.slice(2)).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
module.exports = { download };
