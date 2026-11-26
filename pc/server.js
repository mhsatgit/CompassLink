const http = require("node:http");
const net = require("node:net");
const fs = require("node:fs/promises");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { createController } = require("./controller");
const { WindowsInput } = require("./windows-input");
const { WindowsGamepad } = require("./gamepad/windows-gamepad");
const { normalizeQuaternion, parseMotion } = require("./motion-diagnostics");
const { createInputCapture } = require("./input-capture");
const HOST = "127.0.0.1";
const MAX_FRAME = 32768;
const STANDARD_FRAME = 4096;
const FRAME_BURST = 512;
const MAX_SENSOR_CONNECTIONS = 32;
const CONTENT_POLICY = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "connect-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join("; ");
const ASSETS = new Map([
  ["/", ["index.html", "text/html"]],
  ["/index.html", ["index.html", "text/html"]],
  ["/styles.css", ["styles.css", "text/css"]],
  ["/dashboard.js", ["dashboard.js", "text/javascript"]],
  ["/binding-ui.js", ["binding-ui.js", "text/javascript"]],
  ["/orientation.js", ["orientation.js", "text/javascript"]],
]);

function parsePacket(line) {
  return parseSensorSample(JSON.parse(line));
}

function parseSensorSample(packet) {
  const motion = packet?.motion == null ? null : parseMotion(packet.motion);
  const q = motion && packet.q === null ? null : normalizeQuaternion(packet?.q);
  return {
    q,
    ...(motion ? { motion } : {}),
    sensor:
      typeof packet.sensor === "string"
        ? packet.sensor.slice(0, 128)
        : "unknown",
    t: Number.isFinite(packet.t) ? packet.t : null,
    timestampNs:
      typeof packet.timestampNs === "string"
        ? packet.timestampNs.slice(0, 32)
        : null,
    accuracy: Number.isInteger(packet.accuracy) ? packet.accuracy : null,
    connectionMode: ["usb", "wireless"].includes(packet.connectionMode)
      ? packet.connectionMode
      : "unknown",
  };
}

function readControllerAuthorization() {
  try {
    const authorization = JSON.parse(
      readFileSync(
        path.join(__dirname, "..", ".tools", "controller-authorization.json"),
        "utf8",
      ),
    );
    if (
      typeof authorization?.token === "string" &&
      ["usb", "wireless"].includes(authorization.mode)
    )
      return authorization;
  } catch {
    // A missing or unreadable grant disables controller input. Legacy samples still work.
  }
  return null;
}

function createCompassServer({
  httpPort = 8000,
  sensorPort = 8765,
  engine = new WindowsInput(),
  gamepad = new WindowsGamepad(),
  readAuthorization = readControllerAuthorization,
  controllerTimeoutMs,
} = {}) {
  const state = { last: null, receivedAt: null, count: 0 };
  let motion = null;
  let motionSocket = null;
  let motionAt = null;
  const controller = createController({
    engine,
    gamepad,
    readAuthorization,
    timeoutMs: controllerTimeoutMs,
  });
  const clients = new Set();
  const captures = createInputCapture(readAuthorization);
  const web = http.createServer(async (req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Content-Security-Policy", CONTENT_POLICY);
    res.setHeader("X-Content-Type-Options", "nosniff");
    const host = req.headers.host?.toLowerCase();
    const port = req.socket.localPort;
    if (
      ![HOST, "localhost", `${HOST}:${port}`, `localhost:${port}`].includes(
        host,
      )
    ) {
      res.writeHead(400).end("Use the local CompassLink dashboard address");
      return;
    }
    const pathname = req.url.split("?", 1)[0];
    if (pathname === "/input-capture" && req.method === "POST") {
      await captures.submitCapture(req, res, host);
      return;
    }
    if (req.method !== "GET") {
      res.writeHead(405, { Allow: "GET" }).end();
      return;
    }
    if (pathname === "/api/state") {
      const age =
        state.receivedAt === null
          ? null
          : (Date.now() - state.receivedAt) / 1000;
      res.writeHead(200, {
        "Content-Type": "application/json",
      });
      res.end(
        JSON.stringify({
          ...state,
          age,
          connected: clients.size > 0,
          stale: age === null || age > 2,
          motion: motion
            ? { ...motion, age: (Date.now() - motionAt) / 1000 }
            : null,
          controller: controller.snapshot(),
        }),
      );
      return;
    }
    const asset = ASSETS.get(pathname);
    if (!asset) {
      res.writeHead(404).end("Not found");
      return;
    }
    try {
      const content = await fs.readFile(path.join(__dirname, asset[0]));
      res.writeHead(200, {
        "Content-Type": `${asset[1]}; charset=utf-8`,
      });
      res.end(content);
    } catch {
      res.writeHead(500).end("Unable to load dashboard asset");
    }
  });
  const sensors = net.createServer((socket) => {
    clients.add(socket);
    socket.setEncoding("utf8");
    socket.setNoDelay(true);
    socket.setTimeout(10000, () => socket.destroy());
    let buffer = "";
    let captureOnly = false;
    let allowance = FRAME_BURST;
    let updatedAt = performance.now();
    socket.on("data", (chunk) => {
      if (captureOnly) {
        socket.destroy();
        return;
      }
      const now = performance.now();
      allowance = Math.min(FRAME_BURST, allowance + (now - updatedAt));
      updatedAt = now;
      buffer += chunk;
      let newline;
      while ((newline = buffer.indexOf("\n")) >= 0) {
        if (allowance < 1) {
          socket.destroy();
          return;
        }
        allowance--;
        const frame = buffer.slice(0, newline);
        buffer = buffer.slice(newline + 1);
        const size = Buffer.byteLength(frame);
        if (size > MAX_FRAME) {
          socket.destroy();
          return;
        }
        const line = frame.trim();
        if (!line) continue;
        let packet;
        try {
          packet = JSON.parse(line);
        } catch {
          continue;
        }
        if (
          size > STANDARD_FRAME &&
          !(
            [2, 3, 4, 5].includes(packet?.version) &&
            packet.type === "controller.profile"
          )
        ) {
          socket.destroy();
          return;
        }
        if (packet?.type === "link.check" && packet.version === 1) {
          socket.end('{"type":"link.ready","version":1}\n');
          return;
        }
        if (packet?.type === "capture.listen") {
          captureOnly = true;
          captures.offerCapture(socket, packet);
          return;
        }
        if (packet && Object.hasOwn(packet, "type")) {
          if (!socket.writableEnded) controller.receive(socket, packet);
          continue;
        }
        try {
          const { motion: diagnostics, ...sample } = parseSensorSample(packet);
          const received = Date.now();
          if (diagnostics) {
            motion = diagnostics;
            motionAt = received;
            motionSocket = socket;
          } else if (socket === motionSocket) {
            motion = null;
          }
          if (sample.q) {
            state.last = sample;
            state.receivedAt = received;
            state.count++;
          }
        } catch {
          /* Discard malformed samples without dropping subsequent valid samples. */
        }
      }
      if (Buffer.byteLength(buffer) > MAX_FRAME) socket.destroy();
    });
    socket.on("close", () => {
      clients.delete(socket);
      if (socket === motionSocket) {
        motion = null;
        motionSocket = null;
      }
      controller.disconnect(socket);
    });
    socket.on("error", () => socket.destroy());
  });
  sensors.maxConnections = MAX_SENSOR_CONNECTIONS;
  async function start() {
    const listen = (server, port) =>
      new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, HOST, () => {
          server.removeListener("error", reject);
          resolve();
        });
      });
    try {
      await listen(sensors, sensorPort);
      await listen(web, httpPort);
      engine.start();
      gamepad.start();
    } catch (error) {
      await close();
      throw error;
    }
    return { httpPort: web.address().port, sensorPort: sensors.address().port };
  }
  let closing;
  function close() {
    return (closing ??= shutdown());
  }
  async function shutdown() {
    controller.stop();
    for (const socket of clients) socket.destroy();
    await Promise.all([
      engine.close(),
      gamepad.close(),
      ...[web, sensors].map(
        (server) =>
          new Promise((resolve) => {
            if (!server.listening) {
              resolve();
              return;
            }
            server.close(resolve);
            server.closeIdleConnections?.();
          }),
      ),
    ]);
  }
  return { start, close };
}
if (require.main === module) {
  const app = createCompassServer();
  app
    .start()
    .then(() => {
      console.log("Dashboard: http://127.0.0.1:8000");
      console.log("USB / wireless ADB receiver: 127.0.0.1:8765");
    })
    .catch((error) => {
      console.error(`Cannot start CompassLink3D: ${error.message}`);
      process.exitCode = 1;
    });
  for (const signal of ["SIGINT", "SIGTERM"])
    process.once(signal, () => app.close());
}
module.exports = { createCompassServer, parsePacket };
