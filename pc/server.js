const http = require("node:http");
const net = require("node:net");
const fs = require("node:fs/promises");
const path = require("node:path");
const HOST = "127.0.0.1";
const ASSETS = new Map([
  ["/", ["index.html", "text/html"]],
  ["/index.html", ["index.html", "text/html"]],
  ["/styles.css", ["styles.css", "text/css"]],
  ["/dashboard.js", ["dashboard.js", "text/javascript"]],
  ["/orientation.js", ["orientation.js", "text/javascript"]],
]);

function parsePacket(line) {
  const packet = JSON.parse(line);
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

function createCompassServer({ httpPort = 8000, sensorPort = 8765 } = {}) {
  const state = { last: null, receivedAt: null, count: 0 };
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
    socket.setTimeout(10000, () => socket.destroy());
    let buffer = "";
    socket.on("data", (chunk) => {
      buffer += chunk;
      let newline;
      while ((newline = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (line.length > 4096) {
          socket.destroy();
          return;
        }
        if (!line) continue;
        try {
          state.last = parsePacket(line);
          state.receivedAt = Date.now();
          state.count++;
        } catch {
          /* Discard malformed samples without dropping subsequent valid samples. */
        }
      }
      if (buffer.length > 4096) socket.destroy();
    });
    socket.on("close", () => clients.delete(socket));
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
    } catch (error) {
      await close();
      throw error;
    }
    return { httpPort: web.address().port, sensorPort: sensors.address().port };
  }
  async function close() {
    for (const socket of clients) socket.destroy();
    await Promise.all(
      [web, sensors].map(
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
    );
  }
  return { start, close };
}
if (require.main === module) {
  const app = createCompassServer();
  app
    .start()
    .then(() => {
      console.log("Dashboard: http://127.0.0.1:8000");
      console.log("USB / wireless ADB sensor receiver: 127.0.0.1:8765");
    })
    .catch((error) => {
      console.error(`Cannot start CompassLink3D: ${error.message}`);
      process.exitCode = 1;
    });
  for (const signal of ["SIGINT", "SIGTERM"])
    process.once(signal, () => app.close());
}
module.exports = { createCompassServer, parsePacket };

