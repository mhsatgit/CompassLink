const http = require("node:http");
const net = require("node:net");
const fs = require("node:fs/promises");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { createController } = require("./controller");
const { WindowsInput } = require("./windows-input");
const HOST = "127.0.0.1";
const MAX_FRAME = 32768;
const STANDARD_FRAME = 4096;
const ASSETS = new Map([
  ["/", ["index.html", "text/html"]],
  ["/index.html", ["index.html", "text/html"]],
  ["/styles.css", ["styles.css", "text/css"]],
  ["/dashboard.js", ["dashboard.js", "text/javascript"]],
  ["/orientation.js", ["orientation.js", "text/javascript"]],
]);

function parsePacket(line) {
  return parseSensorSample(JSON.parse(line));
}

function parseSensorSample(packet) {
  if (
    !packet ||
    !Array.isArray(packet.q) ||
    packet.q.length !== 4 ||
    !packet.q.every(Number.isFinite)
  ) {
    throw new Error("Expected four finite quaternion components");
  }
  const norm = Math.hypot(...packet.q);
  if (!Number.isFinite(norm) || norm < 1e-6)
    throw new Error("Invalid quaternion norm");
  return {
    q: packet.q.map((value) => value / norm),
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
  readAuthorization = readControllerAuthorization,
  controllerTimeoutMs,
} = {}) {
  const state = { last: null, receivedAt: null, count: 0 };
  const controller = createController({
    engine,
    readAuthorization,
    timeoutMs: controllerTimeoutMs,
  });
  const clients = new Set();
  const web = http.createServer(async (req, res) => {
    const pathname = new URL(req.url, `http://${HOST}`).pathname;
    if (req.method !== "GET") {
      res.writeHead(405).end();
      return;
    }
    if (pathname === "/api/state") {
      const age =
        state.receivedAt === null
          ? null
          : (Date.now() - state.receivedAt) / 1000;
      res.writeHead(200, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
      });
      res.end(
        JSON.stringify({
          ...state,
          age,
          connected: clients.size > 0,
          stale: age === null || age > 2,
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
        "Cache-Control": "no-store",
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
    socket.on("data", (chunk) => {
      buffer += chunk;
      let newline;
      while ((newline = buffer.indexOf("\n")) >= 0) {
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
          !(packet?.version === 2 && packet.type === "controller.profile")
        ) {
          socket.destroy();
          return;
        }
        if (packet && Object.hasOwn(packet, "type")) {
          if (!socket.writableEnded) controller.receive(socket, packet);
          continue;
        }
        try {
          state.last = parseSensorSample(packet);
          state.receivedAt = Date.now();
          state.count++;
        } catch {
          /* Discard malformed samples without dropping subsequent valid samples. */
        }
      }
      if (Buffer.byteLength(buffer) > MAX_FRAME) socket.destroy();
    });
    socket.on("close", () => {
      clients.delete(socket);
      controller.disconnect(socket);
    });
    socket.on("error", () => socket.destroy());
  });
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
