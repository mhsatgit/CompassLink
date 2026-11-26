const net = require("node:net");
const { EventEmitter } = require("node:events");
const { execFile } = require("node:child_process");
const { promisify } = require("node:util");

const BUTTONS = {
  A: 0x1000,
  B: 0x2000,
  X: 0x4000,
  Y: 0x8000,
  LB: 0x100,
  RB: 0x200,
  START: 0x10,
  BACK: 0x20,
  L3: 0x40,
  R3: 0x80,
};
const DIRECTIONS = { up: 1, down: 2, left: 4, right: 8 };
const bits = (state, map) =>
  Object.keys(map).reduce(
    (mask, key) => (state[key] ? mask | map[key] : mask),
    0,
  );
const flags = (mask, map) =>
  Object.fromEntries(
    Object.entries(map).map(([key, bit]) => [key, Boolean(mask & bit)]),
  );
const normalizeStick = (value) => value / (value < 0 ? 32768 : 32767);

function observedState(packet) {
  return {
    ...packet,
    state: {
      buttons: flags(packet.buttons, BUTTONS),
      dpad: flags(packet.buttons, DIRECTIONS),
      leftStick: {
        x: normalizeStick(packet.leftX),
        y: normalizeStick(packet.leftY),
      },
      rightStick: {
        x: normalizeStick(packet.rightX),
        y: normalizeStick(packet.rightY),
      },
      leftTrigger: packet.leftTrigger / 255,
      rightTrigger: packet.rightTrigger / 255,
    },
  };
}

function encodeReport(state) {
  const report = Buffer.alloc(27);
  report[0] = 2;
  report.writeUInt16LE(
    bits(state.buttons, BUTTONS) | bits(state.dpad, DIRECTIONS),
    1,
  );
  const values = [
    state.leftStick.x,
    state.leftStick.y,
    state.rightStick.x,
    state.rightStick.y,
    state.leftTrigger,
    state.rightTrigger,
  ];
  values.forEach((value, index) => report.writeFloatLE(value, 3 + 4 * index));
  return report;
}

async function accountPipe() {
  const { stdout } = await promisify(execFile)(
    "whoami.exe",
    ["/user", "/fo", "csv", "/nh"],
    {
      windowsHide: true,
    },
  );
  const sid = stdout.match(/\bS-\d+(?:-\d+)+\b/)?.[0];
  if (!sid) throw new Error("Could not determine the Windows account SID.");
  return "\\\\.\\pipe\\CompassLink.Xbox." + sid;
}

class WindowsGamepad extends EventEmitter {
  constructor({
    pipe = accountPipe,
    supported = process.platform === "win32" && process.arch === "x64",
  } = {}) {
    super();
    this.pipe = pipe;
    this.supported = supported;
    this.status = "unavailable";
    this.message = supported
      ? "Start the gamepad helper with npm run start:gamepad."
      : "Virtual Gamepad requires x64 Windows.";
    this.installed = null;
    this.generation = 0;
  }

  get busy() {
    return Boolean(this.link);
  }
  get ready() {
    return this.status === "connected";
  }

  setStatus(status, message) {
    this.status = status;
    this.message = message;
  }

  start() {
    if (!this.supported || this.poll) return;
    const check = () => {
      if (this.busy || this.checking) return;
      this.checking = this.request(0)
        .catch(() => {})
        .finally(() => {
          this.checking = null;
        });
    };
    check();
    this.poll = setInterval(check, 5000);
    this.poll.unref();
  }

  async connect() {
    if (!this.supported) throw new Error(this.message);
    if (this.ready) return;
    const generation = this.generation;
    await this.checking;
    if (this.link?.stopping) await this.link.closed;
    if (generation !== this.generation)
      throw new Error("Virtual controller setup canceled.");
    if (this.ready) return;
    if (this.link?.operation === 1 && !this.link.stopping)
      return this.link.result;
    if (this.link)
      throw new Error(
        "The previous virtual controller is disconnecting. Retry.",
      );
    this.setStatus("initializing", "Waiting for Windows XInput enumeration.");
    return this.request(1);
  }

  request(operation) {
    const link = { operation, stopping: false, ready: false };
    if (operation === 1) this.link = link;
    link.closed = new Promise((resolve) => {
      link.onClosed = resolve;
    });
    link.result = new Promise((resolve, reject) => {
      let timer;
      let finished = false;
      let settled = false;
      let failure;
      let receivedStatus = false;
      let openAttempts = 0;
      const finish = (error) => {
        if (finished) return;
        finished = true;
        link.neutral?.(error);
        clearTimeout(timer);
        if (operation === 0) this.cancelCheck = null;
        if (operation === 1 && this.link === link) {
          this.link = null;
          this.slot = null;
          this.observed = null;
          this.commanded = null;
          if (link.stopping) {
            this.setStatus("disconnected", "Virtual controller disconnected.");
          } else {
            this.setStatus("error", error.message);
            if (link.ready) this.emit("fault", error);
          }
        } else if (operation === 0 && !receivedStatus && !this.busy) {
          this.installed = null;
          this.setStatus("unavailable", error.message);
        }
        link.onClosed();
        if (!settled) reject(error);
      };
      const fail = (error) => {
        failure = error;
        link.socket?.destroy();
        if (operation === 0) finish(error);
      };
      if (operation === 0)
        this.cancelCheck = () => fail(new Error("The desktop server stopped."));
      const open = (name) => {
        if (finished) return;
        if (link.stopping) {
          finish(new Error("Virtual controller setup canceled."));
          return;
        }
        const socket = net.createConnection(name);
        openAttempts++;
        if (operation === 0) socket.unref();
        link.socket = socket;
        socket.setEncoding("utf8");
        timer = setTimeout(
          () => fail(new Error("The gamepad helper did not respond.")),
          operation === 0 ? 1500 : 8000,
        );
        let connected = false;
        let retryOpen = false;
        socket.on("connect", () => {
          connected = true;
          socket.write(Buffer.from([operation]));
        });
        socket.on("error", (error) => {
          const offline =
            error.code === "ENOENT" || error.code === "ECONNREFUSED";
          retryOpen =
            operation === 1 &&
            !connected &&
            !failure &&
            openAttempts < 4 &&
            (offline || error.code === "EBUSY");
          failure ??= offline
            ? new Error(
                "The gamepad helper is offline. Run npm run start:gamepad and retry.",
              )
            : error;
        });
        socket.on("close", () => {
          if (retryOpen && !link.stopping) {
            // The native host opens a fresh pipe after closing each status or controller connection.
            clearTimeout(timer);
            failure = null;
            timer = setTimeout(() => open(name), 50);
          } else {
            finish(failure ?? new Error("The gamepad helper disconnected."));
          }
        });
        let pending = "";
        socket.on("data", (data) => {
          pending += data;
          if (pending.length > 4096) {
            fail(new Error("The gamepad helper sent an oversized response."));
            return;
          }
          let newline;
          while ((newline = pending.indexOf("\n")) >= 0) {
            const line = pending.slice(0, newline);
            pending = pending.slice(newline + 1);
            let packet;
            try {
              packet = JSON.parse(line);
            } catch {
              fail(new Error("Could not read the gamepad helper response."));
              return;
            }
            if (
              packet?.type === "status" &&
              operation === 0 &&
              typeof packet.installed === "boolean"
            ) {
              receivedStatus = true;
              this.installed = packet.installed;
              this.setStatus(
                packet.installed ? "ready" : "installation required",
                packet.installed
                  ? "The gamepad helper is ready for an Android connection."
                  : "Install the signed USB/IP driver with the gamepad setup script.",
              );
            } else if (
              packet?.type === "ready" &&
              operation === 1 &&
              !link.stopping &&
              !link.ready &&
              Number.isInteger(packet.slot) &&
              packet.slot >= 1 &&
              packet.slot <= 4
            ) {
              clearTimeout(timer);
              link.ready = true;
              this.installed = true;
              this.slot = packet.slot;
              this.setStatus(
                "connected",
                "Windows XInput recognized the virtual Xbox controller.",
              );
              settled = true;
              resolve();
            } else if (packet?.type === "neutral" && link.ready) {
              link.neutral?.();
            } else if (
              packet?.type === "observed" &&
              link.ready &&
              packet.slot === this.slot
            ) {
              this.observed = observedState(packet);
            } else if (
              packet?.type === "error" &&
              typeof packet.message === "string"
            ) {
              fail(new Error(packet.message));
              return;
            }
          }
        });
        if (operation === 0)
          socket.once("end", () => {
            if (receivedStatus) {
              settled = true;
              resolve();
            }
          });
      };
      (this.pipeName ??= Promise.resolve().then(() => this.pipe()))
        .then(open)
        .catch((error) => {
          this.pipeName = null;
          finish(error);
        });
    });
    return link.result;
  }

  write(buffer) {
    const link = this.link;
    if (!link?.ready || link.stopping || link.socket.destroyed) return false;
    if (link.socket.writableLength >= 1024) {
      link.socket.destroy(
        new Error("The gamepad helper stopped accepting input."),
      );
      return false;
    }
    link.socket.write(buffer);
    return true;
  }

  submit(state) {
    const report = encodeReport(state);
    if (this.link?.lastReport?.equals(report)) return this.ready;
    if (!this.write(report)) return false;
    this.link.lastReport = report;
    this.commanded = state;
    return true;
  }

  heartbeat() {
    return this.write(Buffer.from([3]));
  }

  neutral() {
    const link = this.link;
    if (!this.ready || !link) return Promise.reject(new Error(this.message));
    if (link.neutralPromise) return link.neutralPromise;
    link.neutralPromise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        const error = new Error(
          "The gamepad helper did not confirm neutral. Restart it with npm run start:gamepad.",
        );
        link.neutral(error);
        link.socket.destroy(error);
      }, 1500);
      link.neutral = (error) => {
        clearTimeout(timeout);
        link.neutral = null;
        if (error) reject(error);
        else resolve();
      };
      if (!this.write(Buffer.from([5]))) link.neutral(new Error(this.message));
    }).finally(() => {
      link.neutralPromise = null;
    });
    return link.neutralPromise;
  }

  disconnect() {
    this.generation++;
    const link = this.link;
    if (!link) return Promise.resolve();
    if (!link.stopping) {
      link.stopping = true;
      this.setStatus("disconnecting", "Releasing the virtual controller.");
      if (link.ready) {
        // Node may close its local pipe handle after end(). Keep reading until the
        // native end closes its handle after neutralizing and disposing the device.
        link.socket.write(Buffer.from([4]));
      } else {
        link.socket?.destroy();
      }
    }
    return link.closed;
  }

  async close() {
    clearInterval(this.poll);
    this.cancelCheck?.();
    await Promise.all([this.disconnect(), this.checking]);
  }

  snapshot() {
    const { status, message, installed, slot, commanded, observed } = this;
    return {
      backend: "HIDMaestro 2.3.0 / USB/IP",
      status,
      message,
      installed,
      slot: slot ?? null,
      commanded: commanded ?? null,
      observed: observed ?? null,
    };
  }
}

module.exports = { WindowsGamepad, encodeReport, BUTTONS, DIRECTIONS };
