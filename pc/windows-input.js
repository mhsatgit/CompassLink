const { execFile, spawn } = require("node:child_process");
const { createHash, randomUUID } = require("node:crypto");
const { EventEmitter, once } = require("node:events");
const fs = require("node:fs/promises");
const path = require("node:path");
const readline = require("node:readline");
const { promisify } = require("node:util");

const run = promisify(execFile);
const buildScript = path.join(__dirname, "windows", "InputEngine.ps1");
const source = path.join(__dirname, "windows", "InputEngine.cs");
const cache = path.join(__dirname, "..", ".tools", "windows-input");

async function buildHelper() {
  const hash = createHash("sha256");
  hash.update(await fs.readFile(source));
  hash.update(await fs.readFile(buildScript));
  const binary = path.join(
    cache,
    `InputEngine-${hash.digest("hex").slice(0, 16)}.exe`,
  );
  try {
    await fs.access(binary);
    return binary;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }

  await fs.mkdir(cache, { recursive: true });
  const pending = path.join(cache, `${randomUUID()}.exe`);
  try {
    await run(
      path.join(
        process.env.SystemRoot || "C:\\Windows",
        "System32",
        "WindowsPowerShell",
        "v1.0",
        "powershell.exe",
      ),
      [
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        buildScript,
        "-OutputPath",
        pending,
      ],
      { windowsHide: true, timeout: 12000 },
    );
    try {
      await fs.rename(pending, binary);
    } catch (error) {
      // Another server may have compiled and opened this same version first.
      await fs.access(binary).catch(() => {
        throw error;
      });
    }
    return binary;
  } finally {
    await fs.rm(pending, { force: true });
  }
}

class WindowsInput extends EventEmitter {
  constructor() {
    super();
    this.supported = process.platform === "win32";
    this.ready = false;
    this.message = this.supported
      ? "Starting Windows input engine"
      : "Keyboard output requires Windows";
    this.child = null;
    this.starting = null;
    this.stopping = false;
    this.restartAt = 0;
  }

  start() {
    if (
      !this.supported ||
      this.stopping ||
      this.starting ||
      this.child ||
      Date.now() < this.restartAt
    )
      return;
    this.starting = buildHelper()
      .then((binary) => {
        if (!this.stopping) this.launch(binary);
      })
      .catch((error) => {
        if (this.stopping) return;
        const detail = (
          error.stderr?.trim() ||
          error.code ||
          error.message
        ).split(/\r?\n/)[0];
        this.message = `Cannot build Windows input engine (${detail})`;
        this.restartAt = Date.now() + 1000;
        this.emit("fault");
      })
      .finally(() => {
        this.starting = null;
      });
  }

  launch(binary) {
    const child = spawn(binary, [], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
      // Keep Windows from terminating the helper with Node before it can release keys on EOF.
      detached: true,
    });
    this.child = child;
    let stderr = "";
    let failed = false;
    const timeout = setTimeout(() => {
      fail("Windows input engine startup timed out");
      // The helper has never accepted commands before the ready handshake.
      child.kill();
    }, 8000);
    const fail = (message) => {
      if (failed || this.stopping) return;
      failed = true;
      clearTimeout(timeout);
      this.ready = false;
      this.message = message;
      this.restartAt = Date.now() + 1000;
      this.emit("fault");
      // EOF lets the child release its own keys before exiting.
      child.stdin.end();
    };
    this.fail = fail;
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr = (stderr + chunk).slice(-1200);
    });
    readline.createInterface({ input: child.stdout }).on("line", (line) => {
      if (line === "ready" && !failed && !this.stopping) {
        clearTimeout(timeout);
        this.ready = true;
        this.message = "Windows input engine ready";
      } else if (line.startsWith("error ")) {
        fail(line.slice(6));
      } else if (line === "switch-reset") {
        this.emit("switch-reset");
      } else if (line === "keyboard-hid") {
        this.message = "CompassLink Virtual USB HID Keyboard connected";
      } else if (line === "keyboard-sendinput") {
        this.message =
          "Windows software keyboard ready. Run npm run start:gamepad for HID keyboard input.";
      }
    });
    child.stdin.on("error", (error) => {
      fail(`Windows input engine pipe failed (${error.code || error.message})`);
    });
    child.on("error", (error) => fail(error.message));
    child.on("close", (code) => {
      clearTimeout(timeout);
      this.child = null;
      fail(
        stderr.trim().split(/\r?\n/)[0] ||
          `Windows input engine exited (${code})`,
      );
    });
  }

  send(command) {
    const stdin = this.child?.stdin;
    if (!this.ready || !stdin?.writable) return false;
    if (stdin.writableLength >= 2048) {
      this.fail("Windows input engine is not keeping up");
      return false;
    }
    stdin.write(command + "\n");
    return true;
  }

  release() {
    const child = this.child;
    if (!child) return Promise.resolve(false);
    if (this.releasing) return this.releasing;
    this.releasing = new Promise((resolve) => {
      const finish = (released) => {
        clearTimeout(timeout);
        this.off("switch-reset", confirmed);
        child.off("close", exited);
        resolve(released);
      };
      const confirmed = () => finish(true);
      const exited = (code) => finish(code === 0);
      const timeout = setTimeout(() => finish(false), 3500);
      this.once("switch-reset", confirmed);
      // The helper also releases everything on EOF before closing.
      child.once("close", exited);
      if (!this.send("reset-for-switch")) child.stdin.end();
    }).finally(() => {
      this.releasing = null;
    });
    return this.releasing;
  }

  async close() {
    this.stopping = true;
    this.ready = false;
    await this.starting;
    const child = this.child;
    if (!child) return;
    const closed = once(child, "close");
    child.stdin.end();
    const timeout = setTimeout(() => child.kill(), 5000);
    try {
      await closed;
    } catch {
      // A process that could not be started has no keys to release.
    } finally {
      clearTimeout(timeout);
    }
  }
}

module.exports = { WindowsInput };
