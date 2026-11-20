const { timingSafeEqual } = require("node:crypto");

const VERSION = 1;
const CONTROLLER_KEYS = Object.freeze({
  DPAD_UP: "W",
  DPAD_DOWN: "S",
  DPAD_LEFT: "A",
  DPAD_RIGHT: "D",
  ACTION_A: "SPACE",
  ACTION_B: "ESCAPE",
});
const TOKEN = /^[0-9a-f]{64}$/;
const SESSION = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;

function validPacket(packet) {
  if (
    packet.version !== VERSION ||
    typeof packet.sessionId !== "string" ||
    !SESSION.test(packet.sessionId) ||
    !Number.isSafeInteger(packet.sequence) ||
    packet.sequence < 0
  )
    return false;

  switch (packet.type) {
    case "controller.start":
      return (
        packet.sequence === 0 &&
        typeof packet.token === "string" &&
        TOKEN.test(packet.token)
      );
    case "controller.key":
      return (
        typeof packet.control === "string" &&
        Object.hasOwn(CONTROLLER_KEYS, packet.control) &&
        (packet.action === "DOWN" || packet.action === "UP")
      );
    case "controller.heartbeat":
    case "controller.stop":
      return true;
    default:
      return false;
  }
}

function reply(socket, type, sessionId, message) {
  if (socket.writableLength >= 4096) {
    socket.destroy();
    return;
  }
  socket.write(
    JSON.stringify({ version: VERSION, type, sessionId, message }) + "\n",
  );
}

function createController({ engine, readAuthorization, timeoutMs = 2000 }) {
  let active = null;
  let lastEvent = null;
  let events = 0;
  let dropped = 0;
  let engineErrors = 0;
  let lastStop = null;

  function finish(reason, notify = false) {
    const session = active;
    if (!session) return;
    active = null;
    clearTimeout(session.timer);
    lastStop = reason;
    engine.send("reset");
    if (notify) {
      reply(session.socket, "controller.error", session.id, reason);
      session.socket.end();
    }
  }

  engine.on("fault", () => {
    engineErrors++;
    finish(engine.message, true);
  });

  function refresh() {
    clearTimeout(active.timer);
    active.deadline = performance.now() + timeoutMs;
    active.timer = setTimeout(
      () => finish("Controller heartbeat timed out", true),
      timeoutMs,
    );
  }

  function reject(socket, packet, message) {
    reply(socket, "controller.error", packet.sessionId, message);
  }

  function receive(socket, packet) {
    if (!validPacket(packet)) {
      dropped++;
      return;
    }
    if (active && performance.now() >= active.deadline)
      finish("Controller heartbeat timed out", true);
    if (socket.writableEnded) return;
    if (packet.type === "controller.start") {
      const authorization = readAuthorization();
      if (
        !TOKEN.test(authorization?.token || "") ||
        !timingSafeEqual(
          Buffer.from(packet.token),
          Buffer.from(authorization.token),
        )
      ) {
        dropped++;
        reject(
          socket,
          packet,
          "Run the USB or wireless connection command to authorize this phone",
        );
        return;
      }
      if (active?.socket === socket) {
        dropped++;
        return;
      }
      if (active) finish("Controller session replaced", true);
      engine.start();
      if (!engine.send("alive")) {
        reject(socket, packet, engine.message);
        return;
      }
      active = {
        socket,
        id: packet.sessionId,
        token: authorization.token,
        connectionMode: authorization.mode,
        sequence: 0,
        held: new Set(),
      };
      refresh();
      reply(socket, "controller.ready", active.id);
      return;
    }

    if (
      active?.socket !== socket ||
      active.id !== packet.sessionId ||
      packet.sequence <= active.sequence
    ) {
      dropped++;
      return;
    }
    if (readAuthorization()?.token !== active.token) {
      finish(
        "Controller authorization changed. Reopen the app with the connection command",
        true,
      );
      return;
    }
    active.sequence = packet.sequence;
    if (packet.type === "controller.stop") {
      finish("Controller stopped");
      socket.end();
      return;
    }
    if (packet.type === "controller.heartbeat") {
      if (engine.send("alive")) {
        refresh();
        reply(socket, "controller.heartbeat", active.id);
      } else {
        finish(engine.message, true);
      }
      return;
    }

    const key = CONTROLLER_KEYS[packet.control];
    const down = packet.action === "DOWN";
    if (active.held.has(packet.control) !== down) {
      if (!engine.send(`${down ? "down" : "up"} ${key}`)) {
        finish(engine.message, true);
        return;
      }
      if (down) active.held.add(packet.control);
      else active.held.delete(packet.control);
    }
    lastEvent = {
      control: packet.control,
      key,
      action: packet.action,
      receivedAt: Date.now(),
    };
    events++;
  }

  return {
    receive,
    disconnect(socket) {
      if (active?.socket === socket) finish("Phone disconnected");
    },
    stop() {
      finish("Desktop server stopped");
    },
    snapshot() {
      return {
        active: active !== null,
        connectionMode: active?.connectionMode ?? null,
        engineReady: engine.ready,
        engineMessage: engine.message,
        mappings: CONTROLLER_KEYS,
        held: Array.from(active?.held ?? [], (control) => ({
          control,
          key: CONTROLLER_KEYS[control],
        })),
        lastEvent,
        lastStop,
        events,
        dropped,
        engineErrors,
      };
    },
  };
}

module.exports = { CONTROLLER_KEYS, createController };
