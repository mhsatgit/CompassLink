
const { spawn, execFileSync } = require("node:child_process");
const fs = require("node:fs/promises");
const path = require("node:path");
async function curl(args) {
  await new Promise((resolve, reject) => {
    const child = spawn(
      "curl.exe",
      [
        "--fail",
        "--location",
        "--silent",
        "--show-error",
        "--retry",
        "2",
        ...args,
      ],
      { windowsHide: true, stdio: "inherit" },
    );
    child.on("error", reject);
    child.on("exit", (code) =>
      code === 0
        ? resolve()
        : reject(new Error(`Download exited with ${code}`)),
    );
  });
}
async function download(url, target) {
  if (!url || !target || !/^https:\/\//i.test(url)) {
    throw new Error("Usage: node scripts/download.cjs https://URL OUTPUT_FILE");
  }
  await fs.mkdir(path.dirname(path.resolve(target)), { recursive: true });
  let headers;
  try {
    headers = execFileSync(
      "curl.exe",
      ["--fail", "--location", "--silent", "--show-error", "--head", url],
      { encoding: "utf8", windowsHide: true },
    );
  } catch {
    // Some download hosts reject HEAD even though GET works.
    await curl(["--output", target, url]);
    return;
  }
  const sizes = [...headers.matchAll(/^Content-Length:\s*(\d+)/gim)];
  const size = Number(sizes.at(-1)?.[1]);
  if (!size || !/Accept-Ranges:\s*bytes/i.test(headers)) {
    await curl(["--output", target, url]);
    return;
  }
  const partRoot = `${target}.parts`;
  await fs.mkdir(partRoot, { recursive: true });
  const count = Math.min(8, size);
  async function clearParts() {
    for (let i = 0; i < count; i++) {
      await fs.unlink(path.join(partRoot, `${i}`)).catch((error) => {
        if (error.code !== "ENOENT") throw error;
      });
    }
    await fs.rmdir(partRoot);
  }
  const results = await Promise.allSettled(
    Array.from({ length: count }, async (_, i) => {
      const start = Math.floor((size * i) / count),
        end = Math.floor((size * (i + 1)) / count) - 1;
      const file = path.join(partRoot, `${i}`);
      if ((await fs.stat(file).catch(() => null))?.size !== end - start + 1) {
        await curl(["--range", `${start}-${end}`, "--output", file, url]);
      }
      if ((await fs.stat(file)).size !== end - start + 1)
        throw new Error("Download server returned an unexpected range size");
      console.log(`Downloaded ${i + 1}/${count} archive parts`);
      return file;
    }),
  );
  if (results.some((result) => result.status === "rejected")) {
    // Wait for all requests before falling back when a proxy ignores byte ranges.
    await curl(["--output", target, url]);
    await clearParts();
    return;
  }
  const parts = results.map((result) => result.value);
  const output = await fs.open(target, "w");
  try {
    for (const part of parts) await output.writeFile(await fs.readFile(part));
  } finally {
    await output.close();
  }
  if ((await fs.stat(target)).size !== size)
    throw new Error("Archive size mismatch");
  await clearParts();
}
if (require.main === module) {
  download(...process.argv.slice(2)).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
module.exports = { download };
