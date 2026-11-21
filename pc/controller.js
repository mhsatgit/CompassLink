const { timingSafeEqual } = require("node:crypto");
const {
  ID,
  validateProfile,
  outputsFor,
  describeBinding,
} = require("./controller-profile");

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
const oneOf = (value, allowed) => allowed.includes(value);
const integer = (value, max) =>
  Number.isInteger(value) && Math.abs(value) <= max;
const axis = (value) => Number.isFinite(value) && Math.abs(value) <= 1;

function validPacket(packet) {
  if (
    !oneOf(packet.version, [1, 2]) ||
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
    case "controller.heartbeat":
    case "controller.stop":
      return true;
    case "controller.key":
      return (
        packet.version === 1 &&
        typeof packet.control === "string" &&
        Object.hasOwn(CONTROLLER_KEYS, packet.control) &&
        oneOf(packet.action, ["DOWN", "UP"])
      );
  }
  if (packet.version !== 2) return false;
  if (oneOf(packet.type, ["controller.profile", "controller.neutral"]))
    return true;
  if (
    typeof packet.control !== "string" ||
    !ID.test(packet.control) ||
    !Number.isSafeInteger(packet.revision) ||
    packet.revision < 1
  )
    return false;
  switch (packet.type) {
    case "controller.input":
      return (
        oneOf(packet.slot, ["press", "up", "down", "left", "right", "tap"]) &&
        oneOf(packet.action, ["DOWN", "UP"])
      );
    case "controller.axis":
      return axis(packet.x) && axis(packet.y);
    case "controller.pointer":
      return (
        integer(packet.dx, 512) &&
        integer(packet.dy, 512) &&
        (packet.dx !== 0 || packet.dy !== 0)
      );
    case "controller.wheel":
      return integer(packet.ticks, 8) && packet.ticks !== 0;
    default:
      return false;
  }
}

function reply(socket, packet, type, extra) {
  if (socket.writableLength >= 4096) {
    socket.destroy();
    return;
  }
  socket.write(
    JSON.stringify({
      version: packet.version,
      type,
      sessionId: packet.id,
      ...extra,
    }) + "\n",
  );
}

function createController({ engine, readAuthorization, timeoutMs = 2000 }) {
  let active = null;
  let lastEvent = null;
  let events = 0;
  let dropped = 0;
  let engineErrors = 0;
  let lastStop = null;
  let profileError = null;
  let mappingRevision = 0;

  function finish(reason, notify = false) {
    const session = active;
    if (!session) return;
    active = null;
    clearTimeout(session.timer);
    lastStop = reason;
    engine.send("reset");
    if (notify) {
      reply(session.socket, session, "controller.error", { message: reason });
      session.socket.end();
    }
  }

  engine.on("fault", () => {
    engineErrors++;
    finish(engine.message, true);
  });

  function refresh(session) {
    clearTimeout(session.timer);
    session.deadline = performance.now() + timeoutMs;
    session.timer = setTimeout(
      () => finish("Controller heartbeat timed out", true),
      timeoutMs,
    );
  }

  function start(socket, packet) {
    const authorization = readAuthorization();
    const session = { version: packet.version, id: packet.sessionId, socket };
    if (
      typeof authorization?.token !== "string" ||
      !TOKEN.test(authorization.token) ||
      !timingSafeEqual(
        Buffer.from(packet.token),
        Buffer.from(authorization.token),
      )
    ) {
      dropped++;
      reply(socket, session, "controller.error", {
        message:
          "Run the USB or wireless connection command to authorize this phone",
      });
      return;
    }
    if (active?.socket === socket) {
      dropped++;
      return;
    }
    if (active) finish("Controller session replaced", true);
    engine.start();
    if (!engine.send("alive")) {
      reply(socket, session, "controller.error", { message: engine.message });
      return;
    }
    Object.assign(session, {
      token: authorization.token,
      connectionMode: authorization.mode,
      sequence: 0,
      held: new Map(),
      owners: new Map(),
      profile: null,
      revision: 0,
      allowance: 64,
      rateAt: performance.now(),
    });
    active = session;
    profileError = null;
    refresh(session);
    reply(socket, session, "controller.ready");
  }

  function reset(session) {
    session.held.clear();
    session.owners.clear();
    return engine.send("reset") && engine.send("alive");
  }

  function synchronize(session, packet) {
    session.profile = null;
    session.revision = 0;
    session.lastAxis = null;
    mappingRevision++;
    if (!reset(session)) {
      finish(engine.message, true);
      return;
    }
    const profile = validateProfile(packet.profile);
    if (!profile) {
      dropped++;
      profileError =
        "Invalid controller profile. Check the controls and bindings.";
      reply(session.socket, session, "controller.profile.error", {
        revision: packet.sequence,
        message: profileError,
      });
      return;
    }
    profileError = null;
    session.profile = profile;
    session.revision = packet.sequence;
    reply(session.socket, session, "controller.profile.ready", {
      revision: session.revision,
    });
  }

  function hold(session, control, slot, binding, action, label) {
    const owner = `${control}:${slot}`;
    const down = action === "DOWN";
    const outputs = outputsFor(binding);
    if (outputs.length === 0) return false;
    if (session.held.has(owner) !== down) {
      for (const { kind, key } of down ? outputs : outputs.reverse()) {
        const output = `${kind}:${key}`;
        const previous = session.owners.get(output) ?? 0;
        const count = previous + (down ? 1 : -1);
        if (
          (down ? previous === 0 : count === 0) &&
          !engine.send(
            `${kind === "mouse" ? "mouse-" : ""}${down ? "down" : "up"} ${key}`,
          )
        ) {
          finish(engine.message, true);
          return true;
        }
        if (count === 0) session.owners.delete(output);
        else session.owners.set(output, count);
      }
      if (down)
        session.held.set(owner, {
          control,
          slot,
          label,
          key: describeBinding(binding),
        });
      else session.held.delete(owner);
    }
    record({ control, slot, label, key: describeBinding(binding), action });
    return true;
  }

  function record(event) {
    lastEvent = { ...event, receivedAt: Date.now() };
    events++;
  }

  function continuous(session, packet, control) {
    if (packet.type === "controller.axis") {
      if (!oneOf(control.type, ["JOYSTICK", "TRIGGER", "SLIDER"])) return false;
      session.lastAxis = { control: control.id, x: packet.x, y: packet.y };
      return true;
    }
    const pointer = packet.type === "controller.pointer";
    const binding = control.bindings.get(pointer ? "pointer" : "wheel");
    if (binding?.type !== (pointer ? "POINTER" : "WHEEL")) return false;
    const now = performance.now();
    session.allowance = Math.min(
      64,
      session.allowance + (now - session.rateAt) * 0.18,
    );
    session.rateAt = now;
    if (session.allowance < 1) return false;
    session.allowance--;
    if (
      !engine.send(
        pointer ? `move ${packet.dx} ${packet.dy}` : `wheel ${packet.ticks}`,
      )
    ) {
      finish(engine.message, true);
      return true;
    }
    record({
      control: control.id,
      label: control.label,
      key: describeBinding(binding),
      action: pointer ? "MOVE" : "SCROLL",
    });
    return true;
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
      start(socket, packet);
      return;
    }
    const session = active;
    if (
      session?.socket !== socket ||
      session.id !== packet.sessionId ||
      session.version !== packet.version ||
      packet.sequence <= session.sequence
    ) {
      dropped++;
      return;
    }
    if (readAuthorization()?.token !== session.token) {
      finish(
        "Controller authorization changed. Reopen the app with the connection command",
        true,
      );
      return;
    }
    session.sequence = packet.sequence;
    switch (packet.type) {
      case "controller.stop":
        finish("Controller stopped");
        socket.end();
        return;
      case "controller.heartbeat":
        if (engine.send("alive")) {
          refresh(session);
          reply(socket, session, "controller.heartbeat");
        } else finish(engine.message, true);
        return;
      case "controller.profile":
        synchronize(session, packet);
        return;
      case "controller.neutral":
        session.lastAxis = null;
        if (!reset(session)) finish(engine.message, true);
        return;
      case "controller.key":
        hold(
          session,
          packet.control,
          "",
          {
            type: "KEYBOARD",
            key: CONTROLLER_KEYS[packet.control],
            modifiers: [],
          },
          packet.action,
        );
        return;
    }
    const control =
      packet.revision === session.revision &&
      session.profile?.controls.get(packet.control);
    if (!control) {
      dropped++;
      return;
    }
    if (packet.type === "controller.input") {
      const binding = control.bindings.get(packet.slot);
      if (
        !binding ||
        !hold(
          session,
          control.id,
          packet.slot,
          binding,
          packet.action,
          control.label,
        )
      )
        dropped++;
    } else if (!continuous(session, packet, control)) dropped++;
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
      const profile = active?.profile;
      const legacy = active?.version !== 2;
      return {
        active: active !== null,
        protocolVersion: active?.version ?? null,
        connectionMode: active?.connectionMode ?? null,
        engineReady: engine.ready,
        engineMessage: engine.message,
        synchronized: active !== null && (legacy || !!profile),
        profile: profile
          ? {
              id: profile.id,
              name: profile.name,
              controls: profile.controls.size,
            }
          : active && legacy
            ? { id: "default", name: "Default Controller", controls: 6 }
            : null,
        profileError,
        mappings: legacy ? CONTROLLER_KEYS : {},
        mappingRevision,
        controls: profile
          ? Array.from(
              profile.controls.values(),
              ({ id, label, bindings }) => ({
                id,
                label,
                bindings: Array.from(bindings, ([slot, binding]) => ({
                  slot,
                  action: describeBinding(binding),
                })),
              }),
            )
          : [],
        held: Array.from(
          active?.held.values() ?? [],
          ({ control, slot, label, key }) =>
            legacy ? { control, key } : { control, slot, label, key },
        ),
        axis: active?.lastAxis ?? null,
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
