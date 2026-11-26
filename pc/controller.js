const { timingSafeEqual } = require("node:crypto");
const { GamepadState, STICKS } = require("./gamepad/gamepad-state");
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
const MOTION_LEASE_MS = 850;
const TOUCH_POINTER_REPORTS_PER_MS = 0.48;
const oneOf = (value, allowed) => allowed.includes(value);
const integer = (value, max) =>
  Number.isInteger(value) && Math.abs(value) <= max;
const axis = (value) => Number.isFinite(value) && Math.abs(value) <= 1;

function validPacket(packet) {
  if (
    !oneOf(packet.version, [1, 2, 3, 4, 5]) ||
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
        TOKEN.test(packet.token) &&
        (packet.version === 5
          ? packet.outputMode === "hybrid"
          : packet.version < 3 ||
            oneOf(packet.outputMode, ["keyboard", "gamepad"]))
      );
    case "controller.heartbeat":
      if (packet.version < 5)
        return packet.version < 4 || typeof packet.motionFresh === "boolean";
      return (
        Array.isArray(packet.freshMotion) &&
        packet.freshMotion.length <= 2 &&
        packet.freshMotion.every(
          (id) => typeof id === "string" && ID.test(id),
        ) &&
        new Set(packet.freshMotion).size === packet.freshMotion.length
      );
    case "controller.stop":
      return (
        packet.keepLink === undefined ||
        (packet.version === 5 && typeof packet.keepLink === "boolean")
      );
    case "controller.key":
      return (
        packet.version === 1 &&
        typeof packet.control === "string" &&
        Object.hasOwn(CONTROLLER_KEYS, packet.control) &&
        oneOf(packet.action, ["DOWN", "UP"])
      );
  }
  if (packet.version < 2) return false;
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

function createController({
  engine,
  gamepad,
  readAuthorization,
  timeoutMs = 2000,
}) {
  let active = null;
  let lastEvent = null;
  let lastGamepadEvent = null;
  let events = 0;
  let dropped = 0;
  let engineErrors = 0;
  let lastStop = null;
  let profileError = null;
  let mappingRevision = 0;
  let startEpoch = 0;
  let keyboardRelease = Promise.resolve(true);
  let lastSwitch = null;

  function finish(reason, notify = false, code) {
    const session = active;
    if (!session) return;
    active = null;
    clearTimeout(session.timer);
    lastStop = reason;
    clearTimeout(session.reportTimer);
    clearMotion(session);
    if (session.gamepad) void gamepad?.disconnect();
    if (
      session.keyboard &&
      (session.held.size || session.version === 5) &&
      engine.release
    )
      keyboardRelease = engine.release();
    else if (session.keyboard) engine.send("reset");
    if (session.switchStarted != null)
      lastSwitch = {
        status: "failed",
        message: reason,
        durationMs: elapsedSwitch(session),
      };
    if (notify) {
      reply(session.socket, session, "controller.error", {
        message: reason,
        code,
      });
      session.socket.end();
    }
  }

  engine.on("fault", () => {
    engineErrors++;
    if (active?.keyboard) finish(engine.message, true);
  });
  gamepad?.on("fault", (error) => {
    if (active?.gamepad) finish(error.message, true);
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
        code: "authorization",
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
    const epoch = ++startEpoch;
    const outputMode = packet.version >= 3 ? packet.outputMode : "keyboard";
    const activate = () => {
      if (epoch !== startEpoch || socket.destroyed || socket.writableEnded)
        return;
      if (readAuthorization()?.token !== authorization.token) {
        reply(socket, session, "controller.error", {
          code: "authorization",
          message: "Controller authorization changed. Reconnect the phone.",
        });
        return;
      }
      if (outputMode === "keyboard") {
        engine.start();
        if (!engine.send("alive")) {
          reply(socket, session, "controller.error", {
            message: engine.message,
          });
          return;
        }
      }
      Object.assign(session, {
        token: authorization.token,
        outputMode,
        keyboard: outputMode === "keyboard",
        gamepad: outputMode === "gamepad" ? new GamepadState() : null,
        connectionMode: authorization.mode,
        sequence: 0,
        held: new Map(),
        owners: new Map(),
        motionTimers: new Map(),
        lastMotions: new Map(),
        profile: null,
        revision: 0,
        continuousBudget: { allowance: 64, updatedAt: performance.now() },
        touchPointerBudget: { allowance: 64, updatedAt: performance.now() },
      });
      active = session;
      profileError = null;
      lastSwitch = null;
      lastEvent = null;
      refresh(session);
      reply(socket, session, "controller.ready");
    };
    if (outputMode === "keyboard" && gamepad?.busy)
      void gamepad.disconnect().then(activate);
    else activate();
  }

  function reset(session) {
    clearMotion(session);
    session.lastMotions.clear();
    session.held.clear();
    session.owners.clear();
    clearTimeout(session.reportTimer);
    session.reportTimer = null;
    session.gamepad?.reset();
    const releasedKeys =
      !session.keyboard || (engine.send("reset") && engine.send("alive"));
    const releasedPad =
      !session.gamepad ||
      !gamepad?.ready ||
      gamepad.submit(session.gamepad.snapshot());
    return releasedKeys && releasedPad;
  }

  function clearMotion(session) {
    for (const timer of session.motionTimers.values()) clearTimeout(timer);
    session.motionTimers.clear();
  }

  function backendError(session) {
    return session.keyboard && !engine.ready
      ? engine.message
      : gamepad?.message || engine.message;
  }

  function elapsedSwitch(session) {
    return Math.round(performance.now() - session.switchStarted);
  }

  function reportGamepad(session, immediate) {
    const flush = () => {
      clearTimeout(session.reportTimer);
      session.reportTimer = null;
      if (active === session && !gamepad.submit(session.gamepad.snapshot()))
        finish(gamepad.message, true);
    };
    if (immediate) flush();
    else session.reportTimer ??= setTimeout(flush, 16);
  }

  function synchronize(session, packet) {
    session.profile = null;
    session.revision = 0;
    session.lastAxis = null;
    session.pendingRevision = packet.sequence;
    if (session.version === 5) session.switchStarted = performance.now();
    mappingRevision++;
    if (!reset(session)) {
      finish(backendError(session), true);
      return;
    }
    const reject = (message) => {
      dropped++;
      profileError = message;
      if (session.version === 5) {
        lastSwitch = {
          status: "failed",
          message,
          durationMs: elapsedSwitch(session),
        };
        session.switchStarted = null;
      }
      reply(session.socket, session, "controller.profile.error", {
        revision: packet.sequence,
        message: profileError,
      });
    };
    const profile = validateProfile(
      packet.profile,
      session.version === 5 ? packet.profile?.outputMode : session.outputMode,
      session.version,
    );
    if (!profile)
      return reject(
        "Invalid controller profile. Check the controls and bindings.",
      );
    if (
      session.version >= 3 &&
      !profile.quickSwitch?.length &&
      !Array.from(profile.controls.values()).some(
        (control) => control.bindings.size,
      )
    )
      return reject(
        "This profile has no bindings for the selected output mode.",
      );
    const current = () =>
      active === session && session.pendingRevision === packet.sequence;
    const acknowledge = () => {
      if (!current()) return;
      profileError = null;
      session.profile = profile;
      session.revision = packet.sequence;
      if (session.version === 5) {
        lastSwitch = {
          status: "ready",
          profile: profile.name,
          durationMs: elapsedSwitch(session),
        };
        session.switchStarted = null;
      }
      reply(session.socket, session, "controller.profile.ready", {
        revision: session.revision,
      });
    };
    if (session.version === 5) {
      void synchronizeHybrid(session, profile, current)
        .then(acknowledge)
        .catch((error) => {
          if (current()) reject(error.message);
        });
      return;
    }
    if (!session.gamepad) return acknowledge();
    if (!gamepad)
      return reject("Virtual Gamepad is unavailable on this desktop.");
    keyboardRelease
      .then(async (released) => {
        if (!current()) return;
        if (!released)
          throw new Error(
            "Keyboard release could not be confirmed. Restart the desktop server and retry.",
          );
        await gamepad.connect();
        if (current()) {
          if (!gamepad.submit(session.gamepad.snapshot()))
            finish(gamepad.message, true);
          else acknowledge();
        }
      })
      .catch((error) => {
        if (current()) reject(error.message);
      });
  }

  async function synchronizeHybrid(session, profile, current) {
    // Keep pipes already acquired by this session leased and neutral across revisions.
    const needsKeyboard =
      profile.mode === "keyboard" || profile.mode === "hybrid";
    const needsGamepad =
      profile.mode === "gamepad" || profile.mode === "hybrid";
    if (session.keyboard && engine.release) keyboardRelease = engine.release();
    if (!(await keyboardRelease))
      throw new Error("Keyboard neutralization could not be confirmed.");
    if (!current()) return;
    if (session.gamepad && gamepad?.ready && gamepad.neutral)
      await gamepad.neutral();
    if (!current()) return;
    if (needsKeyboard && !session.keyboard) {
      engine.start();
      if (!engine.send("alive")) throw new Error(engine.message);
      session.keyboard = true;
      if (engine.release) {
        keyboardRelease = engine.release();
        if (!(await keyboardRelease))
          throw new Error(
            "Windows keyboard and mouse did not confirm readiness.",
          );
        if (!current()) return;
      }
    }
    if (needsGamepad) {
      if (!gamepad)
        throw new Error("Virtual Gamepad is unavailable on this desktop.");
      session.gamepad ??= new GamepadState();
      await gamepad.connect();
      if (current() && !gamepad.submit(session.gamepad.snapshot()))
        throw new Error(gamepad.message);
    }
    // The native reset handshake clears its input lease as well as its state.
    // Rearm it before the phone can send the first input of the new revision.
    if (current() && session.keyboard && !engine.send("alive"))
      throw new Error(engine.message);
  }

  function hold(session, control, slot, binding, action, label) {
    const owner = `${control}:${slot}`;
    const down = action === "DOWN";
    if (binding.type === "GAMEPAD") {
      if (!session.gamepad) return false;
      if (session.gamepad.setButton(owner, binding.target, down)) {
        if (down)
          session.held.set(owner, {
            control,
            slot,
            label,
            key: binding.target,
          });
        else session.held.delete(owner);
        record({ control, slot, label, key: binding.target, action }, true);
        reportGamepad(session, true);
      }
      return true;
    }
    const outputs = outputsFor(binding);
    if (!session.keyboard || outputs.length === 0) return false;
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

  function record(event, gamepadEvent = false) {
    lastEvent = { ...event, receivedAt: Date.now() };
    if (gamepadEvent) lastGamepadEvent = lastEvent;
    events++;
  }

  function continuous(session, packet, control) {
    const analog = control.bindings.get("axis");
    if (packet.type === "controller.axis" && analog?.type === "GAMEPAD_AXIS") {
      const binding = analog;
      if (!session.gamepad) return false;
      const stick = STICKS.has(binding.target);
      if (!stick && (packet.x < 0 || packet.y !== 0)) return false;
      if (
        session.version === 5 &&
        stick &&
        Math.hypot(packet.x, packet.y) > 1.000001
      )
        return false;
      if (
        session.gamepad.setAxis(
          `${control.id}:axis`,
          binding.target,
          packet.x,
          stick ? -packet.y : 0,
        )
      ) {
        session.lastAxis = { control: control.id, x: packet.x, y: packet.y };
        record(
          {
            control: control.id,
            label: control.label,
            key: describeBinding(binding),
            action: "AXIS",
          },
          true,
        );
        reportGamepad(session, packet.x === 0 && packet.y === 0);
      }
      return true;
    }
    if (!session.keyboard || session.profile?.mode === "gamepad") return false;
    if (packet.type === "controller.axis") {
      if (!oneOf(control.type, ["JOYSTICK", "TRIGGER", "SLIDER"])) return false;
      session.lastAxis = { control: control.id, x: packet.x, y: packet.y };
      return true;
    }
    const pointer = packet.type === "controller.pointer";
    const binding = control.bindings.get(pointer ? "pointer" : "wheel");
    if (binding?.type !== (pointer ? "POINTER" : "WHEEL")) return false;
    const now = performance.now();
    const touchPointer = pointer && session.version === 5 && !control.mapping;
    const budget = touchPointer
      ? session.touchPointerBudget
      : session.continuousBudget;
    const rate = touchPointer ? TOUCH_POINTER_REPORTS_PER_MS : 0.18;
    budget.allowance = Math.min(
      64,
      budget.allowance + (now - budget.updatedAt) * rate,
    );
    budget.updatedAt = now;
    if (budget.allowance < 1) return false;
    budget.allowance--;
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

  function renewMotion(session, motion) {
    clearTimeout(session.motionTimers.get(motion.id));
    session.motionTimers.set(
      motion.id,
      setTimeout(() => releaseMotion(session, motion), MOTION_LEASE_MS),
    );
  }

  function releaseMotion(session, motion) {
    clearTimeout(session.motionTimers.get(motion.id));
    session.motionTimers.delete(motion.id);
    if (active !== session) return;
    if (motion.mapping === "STICK") {
      continuous(session, { type: "controller.axis", x: 0, y: 0 }, motion);
    } else if (motion.mapping === "STEERING") {
      for (const [slot, binding] of motion.bindings) {
        if (session.held.has(`${motion.id}:${slot}`))
          hold(session, motion.id, slot, binding, "UP", motion.label);
      }
    }
    if (
      session.lastMotions.has(motion.id) &&
      session.lastMotions.get(motion.id).action !== "neutral" &&
      motion.mapping !== "MOUSE"
    )
      session.lastMotions.set(motion.id, {
        action: "neutral",
        receivedAt: Date.now(),
      });
  }

  function motionValue(packet) {
    switch (packet.type) {
      case "controller.axis":
        return { action: "stick", x: packet.x, y: packet.y };
      case "controller.pointer":
        return { action: "mouse", dx: packet.dx, dy: packet.dy };
      case "controller.input":
        return {
          action: "steering",
          direction: packet.slot,
          down: packet.action === "DOWN",
        };
      default:
        return null;
    }
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
        "authorization",
      );
      return;
    }
    session.sequence = packet.sequence;
    switch (packet.type) {
      case "controller.stop":
        finish("Controller stopped");
        if (!packet.keepLink) socket.end();
        return;
      case "controller.heartbeat":
        if (
          session.version === 5 &&
          session.profile &&
          packet.freshMotion.some(
            (id) => !session.profile.motions.some((motion) => motion.id === id),
          )
        ) {
          dropped++;
          return;
        }
        if (
          (!session.keyboard || engine.send("alive")) &&
          (!session.gamepad || !gamepad?.ready || gamepad.heartbeat())
        ) {
          for (const motion of session.profile?.motions ?? []) {
            const fresh =
              session.version === 5
                ? packet.freshMotion.includes(motion.id)
                : packet.motionFresh;
            if (fresh) renewMotion(session, motion);
            else if (session.motionTimers.has(motion.id))
              releaseMotion(session, motion);
          }
          refresh(session);
          reply(socket, session, "controller.heartbeat");
        } else finish(backendError(session), true);
        return;
      case "controller.profile":
        synchronize(session, packet);
        return;
      case "controller.neutral":
        session.lastAxis = null;
        if (session.version === 5) {
          // A queued neutral after a profile frame must never revive that old revision.
          if (packet.revision !== session.revision || !session.profile) {
            dropped++;
            return;
          }
        }
        if (!reset(session)) finish(backendError(session), true);
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
    const motion = session.profile.motions.includes(control);
    if (
      motion &&
      packet.type === "controller.axis" &&
      Math.hypot(packet.x, packet.y) > 1.000001
    ) {
      dropped++;
      return;
    }
    let accepted;
    if (packet.type === "controller.input") {
      const binding = control.bindings.get(packet.slot);
      const opposite = packet.slot === "left" ? "right" : "left";
      const conflictingSteering =
        motion &&
        packet.action === "DOWN" &&
        session.held.has(`${control.id}:${opposite}`);
      accepted =
        !!binding &&
        !conflictingSteering &&
        hold(
          session,
          control.id,
          packet.slot,
          binding,
          packet.action,
          control.label,
        );
    } else accepted = continuous(session, packet, control);
    if (!accepted) dropped++;
    else if (motion && active === session) {
      renewMotion(session, control);
      session.lastMotions.set(control.id, {
        ...motionValue(packet),
        receivedAt: Date.now(),
      });
    }
  }

  return {
    receive,
    disconnect(socket) {
      if (active?.socket === socket) finish("Phone disconnected");
    },
    stop() {
      startEpoch++;
      finish("Desktop server stopped");
    },
    snapshot() {
      const profile = active?.profile;
      const legacy = (active?.version ?? 1) === 1;
      return {
        active: active !== null,
        protocolVersion: active?.version ?? null,
        connectionMode: active?.connectionMode ?? null,
        outputMode: profile?.mode ?? active?.outputMode ?? null,
        profileRevision: active?.revision || null,
        hybrid:
          active?.version === 5
            ? {
                orientationPolicy: profile?.orientationPolicy ?? null,
                haptics: profile?.haptics ?? null,
                gyroActivation: profile?.gyroActivation ?? null,
                quickSwitch: profile?.quickSwitch ?? [],
                keyboard: Boolean(active.keyboard && engine.ready),
                gamepad: Boolean(active.gamepad && gamepad?.ready),
                digitalOwners: Object.fromEntries(active.owners),
                analog: active.gamepad?.ownership() ?? null,
              }
            : null,
        lastSwitch,
        gamepad: gamepad?.snapshot() ?? null,
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
        motion: profile?.motion
          ? {
              id: profile.motion.id,
              mapping: profile.motion.mapping,
              source: profile.motion.source,
              fresh: active.motionTimers.has(profile.motion.id),
              last: active.lastMotions.get(profile.motion.id) ?? null,
            }
          : null,
        motions:
          profile?.motions.map(({ id, mapping, source }) => ({
            id,
            mapping,
            source,
            fresh: active.motionTimers.has(id),
            last: active.lastMotions.get(id) ?? null,
          })) ?? [],
        lastEvent,
        lastGamepadEvent,
        lastStop,
        events,
        dropped,
        engineErrors,
      };
    },
  };
}

module.exports = { CONTROLLER_KEYS, createController };
