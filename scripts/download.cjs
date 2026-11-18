
const { spawn, execFileSync } = require("node:child_process");
const fs = require("node:fs/promises");
const path = require("node:path");
const [url, target] = process.argv.slice(2);
async function curl(args) {
  await new Promise((resolve, reject) => {
    const child = spawn(
      "curl.exe",
      [
        "--fail",
        "--location",
        "--silent",
        "--show-error",
        "--connect-timeout",
        "30",
        "--max-time",
        "300",
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
async function main() {
  const headers = execFileSync(
    "curl.exe",
    [
      "--fail",
      "--location",
      "--silent",
      "--show-error",
      "--head",
      "--max-time",
      "30",
      url,
    ],
    { encoding: "utf8", windowsHide: true },
  );
  const sizes = [...headers.matchAll(/^Content-Length:\s*(\d+)/gim)];
  const size = Number(sizes.at(-1)?.[1]);
  if (!size || !/Accept-Ranges:\s*bytes/i.test(headers)) {
    await curl(["--output", target, url]);
    return;
  }
  const partRoot = `${target}.parts`;
  await fs.mkdir(partRoot, { recursive: true });
  const count = 8;
  const partSize = Math.ceil(size / count);
  const parts = await Promise.all(
    Array.from({ length: count }, async (_, i) => {
      const start = i * partSize,
        end = Math.min(size - 1, start + partSize - 1);
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
  const output = await fs.open(target, "w");
  try {
    for (const part of parts) await output.writeFile(await fs.readFile(part));
  } finally {
    await output.close();
  }
  if ((await fs.stat(target)).size !== size)
    throw new Error("Archive size mismatch");
  for (const part of parts) await fs.unlink(part);
  await fs.rmdir(partRoot);
}
main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
