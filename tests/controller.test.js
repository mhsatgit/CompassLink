const { test } = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter, once } = require("node:events");
const { randomUUID } = require("node:crypto");
const net = require("node:net");
const readline = require("node:readline");
const { createCompassServer } = require("../pc/server");
const { CONTROLLER_KEYS } = require("../pc/controller");
const { WindowsInput } = require("../pc/windows-input");

class TestEngine extends EventEmitter {
  ready = true;
  message = "Windows input engine ready";
  commands = [];
  start() {}
  close() {}
  send(command) {
    if (!this.ready) return false;
    this.commands.push(command);
    return true;
  }
}

class TestGamepad extends EventEmitter {
  ready = false;
  busy = false;
  reports = [];
  start() {}
  close() {}
  async connect() {
    await this.gate;
    this.ready = this.busy = true;
  }
  submit(report) {
    this.reports.push(report);
    return this.ready;
  }
  heartbeat() {
    return this.ready;
  }
  async disconnect() {
    await this.release;
    this.ready = this.busy = false;
  }
  snapshot() {
    return { commanded: this.reports.at(-1) };
  }
}

const token = "a".repeat(64);

async function setup(t, options = {}) {
  const engine = new TestEngine();
  let authorization = { token, mode: "usb" };
  const app = createCompassServer({
    httpPort: 0,
    sensorPort: 0,
    engine,
    gamepad: new TestGamepad(),
    readAuthorization: () => authorization,
    ...options,
  });
  t.after(() => app.close());
  const { httpPort, sensorPort } = await app.start();
  return {
    app,
    engine,
    authorize: (grant) => {
      authorization = grant;
    },
    state: async () =>
      (await fetch(`http://127.0.0.1:${httpPort}/api/state`)).json(),
    phone: async (version = 1) => {
      const socket = net.createConnection(sensorPort, "127.0.0.1");
      await once(socket, "connect");
      const replies = readline.createInterface({ input: socket });
      const sessionId = randomUUID();
      async function request(type, sequence, fields, fragmentSize) {
        const response = once(replies, "line", {
          signal: AbortSignal.timeout(2000),
        });
        send(type, sequence, fields, fragmentSize);
        return JSON.parse((await response)[0]);
      }
      function send(type, sequence, fields, fragmentSize) {
        const frame =
          JSON.stringify({
            version,
            type: `controller.${type}`,
            sessionId,
            sequence,
            ...fields,
          }) + "\n";
        const size = fragmentSize ?? frame.length;
        for (let start = 0; start < frame.length; start += size)
          socket.write(frame.slice(start, start + size));
      }
      return { socket, sessionId, send, request };
    },
  };
}

async function waitForState(state, predicate) {
  for (let i = 0; i < 40; i++) {
    const sample = await state();
    if (predicate(sample.controller)) return sample;
    await new Promise((resolve) => setTimeout(resolve, 15));
  }
  assert.fail("Expected controller state was not received");
}

function hybridProfile() {
  const button = (id, binding) => ({
    id,
    label: id,
    type: "BUTTON",
    bindings: { press: binding },
  });
  return {
    id: "hybrid",
    name: "Hybrid layout",
    outputMode: "hybrid",
    orientationPolicy: "LANDSCAPE",
    haptics: "LIGHT",
    gyroActivation: {
      source: "CONTROL",
      behavior: "HOLD",
      control: "shoulder",
      slot: "press",
    },
    quickSwitch: [{ control: "switch", target: "default" }],
    motions: [
      {
        id: "tilt",
        source: "ORIENTATION",
        mapping: "STICK",
        stick: "LEFT_STICK",
      },
      { id: "gyro", source: "GYROSCOPE", mapping: "MOUSE" },
    ],
    controls: [
      button("key", { type: "KEYBOARD", key: "W" }),
      button("key2", { type: "KEYBOARD", key: "W" }),
      button("fire", { type: "MOUSE_BUTTON", button: "LEFT" }),
      button("shoulder", { type: "GAMEPAD", target: "RB" }),
      button("right", { type: "GAMEPAD", target: "DPAD_RIGHT" }),
      button("left", { type: "GAMEPAD", target: "DPAD_LEFT" }),
      {
        id: "touch",
        label: "Stick",
        type: "JOYSTICK",
        bindings: { axis: { type: "GAMEPAD_AXIS", target: "LEFT_STICK" } },
      },
      {
        id: "scroll",
        label: "Scroll",
        type: "SLIDER",
        bindings: {
          wheel: { type: "WHEEL" },
          axis: { type: "GAMEPAD_AXIS", target: "RIGHT_TRIGGER" },
        },
      },
      { id: "switch", label: "Switch", type: "PROFILE_SWITCH", bindings: {} },
    ],
  };
}

test("v5 authenticates hybrid input, reports digital owners, and arbitrates concurrent touch and motion", async (t) => {
  const { phone, state, engine } = await setup(t);
  const client = await phone(5);
  const start = { outputMode: "hybrid", token };
  assert.equal(
    (await client.request("start", 0, { ...start, token: "b".repeat(64) }))
      .code,
    "authorization",
  );
  client.send("axis", 1, { control: "tilt", revision: 1, x: 1, y: 0 });
  assert.equal(
    (await client.request("start", 0, start)).type,
    "controller.ready",
  );
  const profile = hybridProfile();
  assert.equal(
    (await client.request("profile", 1, { profile })).type,
    "controller.profile.ready",
  );
  let sequence = 1;
  const send = (type, control, fields) =>
    client.send(type, ++sequence, { control, revision: 1, ...fields });
  const digital = (control, action = "DOWN") =>
    send("input", control, { slot: "press", action });
  const axis = (control, x, y = 0) => send("axis", control, { x, y });
  const pulse = (freshMotion) =>
    client.request("heartbeat", ++sequence, { freshMotion });
  for (const control of [
    "key",
    "key",
    "key2",
    "fire",
    "shoulder",
    "left",
    "right",
  ])
    digital(control);
  axis("touch", 0.25);
  axis("tilt", -0.75);
  axis("scroll", 0.6);
  send("pointer", "gyro", { dx: 31, dy: -2 });
  send("wheel", "scroll", { ticks: 2 });
  await pulse(["tilt", "gyro"]);
  const report = await waitForState(
    state,
    ({ gamepad }) => gamepad.commanded.rightTrigger === 0.6,
  );
  const controller = report.controller;
  assert.equal(controller.outputMode, "hybrid");
  assert.equal(controller.profileRevision, 1);
  assert.equal(controller.hybrid.digitalOwners["key:W"], 2);
  assert.equal(controller.hybrid.analog.axes.LEFT_STICK, "tilt:axis");
  assert.equal(controller.gamepad.commanded.leftStick.x, -0.75);
  assert.equal(controller.gamepad.commanded.buttons.RB, true);
  assert.deepEqual(controller.gamepad.commanded.dpad, {
    up: false,
    down: false,
    left: false,
    right: false,
  });
  assert.deepEqual(
    controller.motions.map(({ fresh }) => fresh),
    [true, true],
  );
  assert.equal(JSON.stringify(report).includes(token), false);
  assert.equal(
    engine.commands.filter((command) => command === "down W").length,
    1,
  );
  assert.ok(engine.commands.includes("mouse-down LEFT"));
  assert.ok(engine.commands.includes("move 31 -2"));
  assert.ok(engine.commands.includes("wheel 2"));
  digital("key", "UP");
  digital("left", "UP");
  axis("tilt", 0);
  axis("touch", 1, 1); // v5 rejects vectors outside the unit circle.
  axis("scroll", -0.2);
  await pulse(["gyro"]);
  const restored = (await state()).controller;
  assert.equal(restored.gamepad.commanded.leftStick.x, 0.25);
  assert.equal(restored.gamepad.commanded.rightTrigger, 0.6);
  assert.equal(restored.gamepad.commanded.dpad.right, true);
  assert.equal(restored.hybrid.analog.restored.owner, "touch:axis");
  assert.equal(engine.commands.includes("up W"), false);
  digital("key2", "UP");
  await pulse([]);
  assert.equal(
    engine.commands.filter((command) => command === "up W").length,
    1,
  );
  client.socket.end();
});

test("v5 forwards touch movement samples in order without using the wheel or gyro budget", async (t) => {
  const { phone, engine, state } = await setup(t);
  const client = await phone(5);
  await client.request("start", 0, { outputMode: "hybrid", token });
  const profile = hybridProfile();
  profile.controls.push({
    id: "look",
    label: "Full-screen touch-look",
    type: "TOUCHPAD",
    bindings: { pointer: { type: "POINTER" } },
  });
  assert.equal(
    (await client.request("profile", 1, { profile })).type,
    "controller.profile.ready",
  );
  const commands = [];
  for (let i = 1; i <= 64; i++) {
    client.send("pointer", i + 1, {
      revision: 1,
      control: "look",
      dx: i,
      dy: -1,
    });
    commands.push(`move ${i} -1`);
  }
  client.send("wheel", 66, { revision: 1, control: "scroll", ticks: 1 });
  client.send("pointer", 67, { revision: 1, control: "gyro", dx: -100, dy: 1 });
  commands.push("wheel 1", "move -100 1");
  await client.request("heartbeat", 68, { freshMotion: ["gyro"] });
  assert.deepEqual(
    engine.commands.filter((command) => /^(move|wheel) /.test(command)),
    commands,
  );
  assert.equal((await state()).controller.dropped, 0);
  client.socket.end();
});

test("v5 switch holds both backends neutral before acknowledging and drops revision-scoped input", async (t) => {
  const gamepad = new TestGamepad();
  const { phone, state, engine } = await setup(t, { gamepad });
  const client = await phone(5);
  await client.request("start", 0, { outputMode: "hybrid", token });
  const profile = hybridProfile();
  assert.equal((await client.request("profile", 1, { profile })).revision, 1);
  client.send("input", 2, {
    revision: 1,
    control: "key",
    slot: "press",
    action: "DOWN",
  });
  client.send("axis", 3, { revision: 1, control: "touch", x: 0.8, y: 0 });
  let neutralize;
  gamepad.neutral = () =>
    new Promise((resolve) => {
      neutralize = resolve;
    });
  const next = { ...profile, id: "next", name: "Next layout" };
  const acknowledgment = client.request("profile", 4, { profile: next });
  await waitForState(state, (status) => !status.synchronized && !!neutralize);
  client.send("pointer", 5, { revision: 1, control: "gyro", dx: 333, dy: 0 });
  client.send("input", 6, {
    revision: 4,
    control: "key",
    slot: "press",
    action: "DOWN",
  });
  await waitForState(state, (status) => status.dropped >= 2);
  assert.ok(engine.commands.includes("reset"));
  assert.equal(gamepad.reports.at(-1).leftStick.x, 0);
  neutralize();
  assert.equal((await acknowledgment).revision, 4);
  client.send("neutral", 7, { revision: 1 });
  client.send("input", 8, {
    revision: 4,
    control: "fire",
    slot: "press",
    action: "DOWN",
  });
  client.send("axis", 9, { revision: 4, control: "touch", x: -0.5, y: 0 });
  await client.request("heartbeat", 10, { freshMotion: [] });
  const active = (await state()).controller;
  assert.equal(active.lastSwitch.status, "ready");
  assert.ok(active.lastSwitch.durationMs >= 0);
  assert.deepEqual(
    active.held.map(({ control }) => control),
    ["fire"],
  );
  assert.ok(!engine.commands.includes("move 333 0"));
  assert.equal(gamepad.busy, true);
  gamepad.emit("fault", new Error("native lease failed"));
  const stopped = await waitForState(state, (status) => !status.active);
  assert.equal(stopped.controller.lastStop, "native lease failed");
  assert.equal(engine.commands.at(-1), "reset");
  assert.equal(gamepad.ready, false);
});

test("v5 rejects invalid gyro and quick-switch references without enabling input", async (t) => {
  const { engine, phone, state } = await setup(t);
  const client = await phone(5);
  await client.request("start", 0, { outputMode: "hybrid", token });
  const profile = hybridProfile();
  for (const [index, invalid] of [
    { ...profile, gyroActivation: { ...profile.gyroActivation, slot: "left" } },
    { ...profile, quickSwitch: [{ control: "switch", target: "hybrid" }] },
    { ...profile, quickSwitch: [{ control: "key", target: "default" }] },
    { ...profile, haptics: 1 },
    { ...profile, outputMode: "gamepad" },
    {
      ...profile,
      motions: [profile.motions[0], { ...profile.motions[0], id: "second" }],
    },
  ].entries()) {
    const result = await client.request("profile", index + 1, {
      profile: invalid,
    });
    assert.equal(result.type, "controller.profile.error");
  }
  client.send("input", 10, {
    revision: 6,
    control: "key",
    slot: "press",
    action: "DOWN",
  });
  await client.request("heartbeat", 11, { freshMotion: [] });
  assert.equal((await state()).controller.lastSwitch.status, "failed");
  assert.equal(engine.commands.length, 0);
  client.socket.end();
});

test("authorized controller and legacy sensor data share the same framed socket", async (t) => {
  const { engine, state, phone } = await setup(t);
  const { socket, request, send } = await phone();
  assert.equal((await request("start", 0, { token })).type, "controller.ready");
  socket.write('{"q":[0,0,');
  socket.write('0,2],"connectionMode":"usb"}\n');
  assert.deepEqual(CONTROLLER_KEYS, {
    DPAD_UP: "W",
    DPAD_DOWN: "S",
    DPAD_LEFT: "A",
    DPAD_RIGHT: "D",
    ACTION_A: "SPACE",
    ACTION_B: "ESCAPE",
  });
  send("key", 1, { control: "DPAD_UP", action: "DOWN" });
  send("key", 2, { control: "DPAD_LEFT", action: "DOWN" });
  send("key", 3, { control: "DPAD_UP", action: "DOWN" });
  for (const [i, control] of [
    "DPAD_DOWN",
    "DPAD_RIGHT",
    "ACTION_A",
    "ACTION_B",
  ].entries()) {
    send("key", 4 + i * 2, { control, action: "DOWN" });
    send("key", 5 + i * 2, { control, action: "UP" });
  }
  send("key", 12, { control: "DPAD_LEFT", action: "UP" });
  send("key", 13, { control: "DPAD_LEFT", action: "UP" });
  assert.equal((await request("heartbeat", 14)).type, "controller.heartbeat");
  const data = await state();
  assert.deepEqual(data.last.q, [0, 0, 0, 1]);
  assert.equal(data.count, 1);
  assert.deepEqual(data.controller.held, [{ control: "DPAD_UP", key: "W" }]);
  assert.equal(data.controller.lastEvent.action, "UP");
  assert.equal(data.controller.lastEvent.key, "A");
  assert.deepEqual(engine.commands, [
    "alive",
    "down W",
    "down A",
    "down S",
    "up S",
    "down D",
    "up D",
    "down SPACE",
    "up SPACE",
    "down ESCAPE",
    "up ESCAPE",
    "up A",
    "alive",
  ]);
  socket.end();
  await waitForState(state, (controller) => !controller.active);
  assert.equal(engine.commands.at(-1), "reset");
});

test("rejects unauthorized, malformed, unknown, duplicate and stale controller frames", async (t) => {
  const { engine, state, phone } = await setup(t);
  const client = await phone();
  const denied = await client.request("start", 0, { token: "b".repeat(64) });
  assert.equal(denied.type, "controller.error");
  assert.equal(denied.code, "authorization");
  client.send("key", 1, { control: "DPAD_UP", action: "DOWN" });
  assert.equal(
    (await client.request("start", 0, { token })).type,
    "controller.ready",
  );
  client.send("key", 1, { control: "DPAD_UP", action: "DOWN", version: 2 });
  client.send("key", 1, { control: "constructor", action: "DOWN" });
  client.send("key", 1, { control: "DPAD_UP", action: "EXEC" });
  client.send("key", 1, { control: "DPAD_UP", action: "DOWN", sequence: "1" });
  client.send("key", 1, {
    control: "DPAD_UP",
    action: "DOWN",
    sessionId: randomUUID(),
  });
  client.send("command", 1, { q: [0, 0, 0, 1] });
  client.send("key", 2, { control: "DPAD_UP", action: "DOWN" });
  client.send("key", 2, { control: "DPAD_UP", action: "UP" });
  client.send("key", 1, { control: "DPAD_UP", action: "UP" });
  await client.request("heartbeat", 3);
  const data = await state();
  assert.equal(data.count, 0);
  assert.equal(data.controller.dropped, 10);
  assert.equal(data.controller.events, 1);
  assert.deepEqual(engine.commands, ["alive", "down W", "alive"]);
  client.send("stop", 4);
  await waitForState(state, (controller) => !controller.active);
  assert.equal(engine.commands.at(-1), "reset");
});

test("heartbeats time out, replaced sessions go neutral, and shutdown resets input", async (t) => {
  const { app, engine, state, phone } = await setup(t, {
    controllerTimeoutMs: 250,
  });
  const first = await phone();
  await first.request("start", 0, { token });
  first.send("key", 1, { control: "DPAD_UP", action: "DOWN" });
  await first.request("heartbeat", 2);
  const replacement = await phone();
  await replacement.request("start", 0, { token });
  assert.deepEqual((await state()).controller.held, []);
  assert.deepEqual(engine.commands.slice(-2), ["reset", "alive"]);
  replacement.send("key", 1, { control: "ACTION_A", action: "DOWN" });
  await replacement.request("heartbeat", 2);
  const afterTimeout = await waitForState(
    state,
    (controller) => !controller.active,
  );
  assert.equal(
    afterTimeout.controller.lastStop,
    "Controller heartbeat timed out",
  );
  assert.deepEqual(afterTimeout.controller.held, []);
  assert.equal(engine.commands.at(-1), "reset");
  const last = await phone();
  await last.request("start", 0, { token });
  last.send("key", 1, { control: "ACTION_B", action: "DOWN" });
  await last.request("heartbeat", 2);
  await app.close();
  assert.equal(engine.commands.at(-1), "reset");
});

test("a new ADB grant or a Windows input failure invalidates the current session", async (t) => {
  const { engine, state, phone, authorize } = await setup(t);
  const first = await phone();
  await first.request("start", 0, { token });
  first.send("key", 1, { control: "DPAD_UP", action: "DOWN" });
  await first.request("heartbeat", 2);
  const wirelessToken = "c".repeat(64);
  authorize({ token: wirelessToken, mode: "wireless" });
  const revoked = await first.request("heartbeat", 3);
  assert.equal(revoked.type, "controller.error");
  assert.equal(revoked.code, "authorization");
  assert.deepEqual((await state()).controller.held, []);
  assert.equal(engine.commands.at(-1), "reset");
  const wireless = await phone();
  await wireless.request("start", 0, { token: wirelessToken });
  assert.equal((await state()).controller.connectionMode, "wireless");
  engine.ready = false;
  engine.message = "Windows SendInput failed";
  engine.emit("fault");
  const result = (await state()).controller;
  assert.equal(result.active, false);
  assert.equal(result.engineReady, false);
  assert.equal(result.engineErrors, 1);
  assert.equal(result.lastStop, engine.message);
});

test("keyboard and mouse release requires a reset acknowledgement or clean exit", async () => {
  const engine = new WindowsInput();
  assert.equal(await engine.release(), false);
  engine.send = (command) => {
    assert.equal(command, "reset-for-switch");
    return true;
  };
  const child = new EventEmitter();
  engine.child = child;
  const failed = engine.release();
  child.emit("close", 1);
  assert.equal(await failed, false);
  const confirmed = engine.release();
  engine.emit("switch-reset");
  assert.equal(await confirmed, true);
  const clean = engine.release();
  child.emit("close", 0);
  assert.equal(await clean, true);
});

const key = (key, ...modifiers) => ({ type: "KEYBOARD", key, modifiers });
const mouse = (button) => ({ type: "MOUSE_BUTTON", button });
const control = (id, type, bindings) => ({ id, type, label: id, bindings });
const gamepadBinding = (target) => ({ type: "GAMEPAD", target });
const gamepadAxis = (target) => ({ type: "GAMEPAD_AXIS", target });
const xbox = {
  id: "xbox",
  name: "Xbox Controller",
  controls: [
    control("a", "BUTTON", { press: gamepadBinding("A") }),
    control("duplicate", "BUTTON", { press: gamepadBinding("A") }),
    control("left", "JOYSTICK", { axis: gamepadAxis("LEFT_STICK") }),
    control("look", "TOUCHPAD", { axis: gamepadAxis("RIGHT_STICK") }),
    control("lt", "TRIGGER", { axis: gamepadAxis("LEFT_TRIGGER") }),
  ],
};

test("gamepad protocol waits for native readiness, validates revisions, and releases before keyboard activation", async (t) => {
  const gamepad = new TestGamepad();
  let ready;
  gamepad.gate = new Promise((resolve) => {
    ready = resolve;
  });
  const { state, phone, engine } = await setup(t, { gamepad });
  const client = await phone(3);
  const outputMode = "gamepad";
  assert.equal(
    (await client.request("start", 0, { token: "b".repeat(64), outputMode }))
      .type,
    "controller.error",
  );
  assert.equal(
    (await client.request("start", 0, { token, outputMode })).type,
    "controller.ready",
  );
  const invalid = {
    ...xbox,
    controls: [
      control("wrong", "TRIGGER", { axis: gamepadAxis("RIGHT_STICK") }),
    ],
  };
  assert.equal(
    (await client.request("profile", 1, { profile: invalid })).type,
    "controller.profile.error",
  );
  const synchronized = client.request("profile", 2, { profile: xbox });
  const action = (sequence, control, action, revision = 2) =>
    client.send("input", sequence, {
      revision,
      control,
      slot: "press",
      action,
    });
  action(3, "a", "DOWN");
  const waiting = await waitForState(state, (result) => result.dropped >= 3);
  assert.equal(waiting.controller.synchronized, false);
  assert.deepEqual(gamepad.reports, []);
  ready();
  assert.equal((await synchronized).type, "controller.profile.ready");
  action(4, "a", "DOWN", 1);
  action(5, "a", "DOWN");
  action(6, "duplicate", "DOWN");
  action(7, "a", "UP");
  client.send("axis", 8, { control: "left", revision: 2, x: 0.25, y: -0.5 });
  client.send("axis", 9, { control: "lt", revision: 2, x: 0.8, y: 0.2 });
  client.send("axis", 10, { control: "lt", revision: 2, x: 0.8, y: 0 });
  const moved = await waitForState(
    state,
    (result) => result.gamepad.commanded?.leftTrigger === 0.8,
  );
  assert.equal(moved.controller.gamepad.commanded.buttons.A, true);
  assert.deepEqual(moved.controller.gamepad.commanded.leftStick, {
    x: 0.25,
    y: 0.5,
  });
  assert.deepEqual(engine.commands, []);

  let release;
  gamepad.release = new Promise((resolve) => {
    release = resolve;
  });
  const keyboard = await phone(3);
  const keyboardReady = keyboard.request("start", 0, {
    token,
    outputMode: "keyboard",
  });
  await waitForState(state, (result) => !result.active);
  assert.deepEqual(engine.commands, []);
  release();
  assert.equal((await keyboardReady).type, "controller.ready");
  assert.equal(gamepad.ready, false);
  assert.deepEqual(engine.commands, ["alive"]);

  await keyboard.request("profile", 1, { profile: sampleProfile });
  keyboard.send("input", 2, {
    revision: 1,
    control: "jump",
    slot: "press",
    action: "DOWN",
  });
  await keyboard.request("heartbeat", 3);
  let confirmReset;
  engine.release = () =>
    new Promise((resolve) => {
      confirmReset = resolve;
    });
  keyboard.socket.destroy();
  await waitForState(state, (result) => !result.active);
  const next = await phone(3);
  await next.request("start", 0, { token, outputMode });
  const acknowledged = next.request("profile", 1, { profile: xbox });
  await waitForState(state, (result) => result.mappingRevision === 4);
  assert.equal(gamepad.ready, false);
  confirmReset(true);
  assert.equal((await acknowledged).type, "controller.profile.ready");
});

test("gamepad neutralization, helper failure, timeout, and reconnect clear a prior profile", async (t) => {
  const gamepad = new TestGamepad();
  const { state, phone } = await setup(t, {
    gamepad,
    controllerTimeoutMs: 350,
  });
  const connect = async () => {
    const client = await phone(3);
    await client.request("start", 0, { token, outputMode: "gamepad" });
    assert.equal(
      (await client.request("profile", 1, { profile: xbox })).type,
      "controller.profile.ready",
    );
    return client;
  };
  const first = await connect();
  first.send("input", 2, {
    control: "a",
    slot: "press",
    action: "DOWN",
    revision: 1,
  });
  first.send("axis", 3, { control: "left", x: 1, y: 0, revision: 1 });
  await waitForState(
    state,
    (result) => result.gamepad.commanded?.leftStick.x === 1,
  );
  first.send("neutral", 4);
  await waitForState(state, (result) => !result.gamepad.commanded?.buttons.A);
  assert.equal(gamepad.reports.at(-1).leftStick.x, 0);
  gamepad.emit("fault", new Error("Native controller disappeared"));
  const stopped = await waitForState(state, (result) => !result.active);
  assert.equal(stopped.controller.lastStop, "Native controller disappeared");
  await connect();
  assert.equal(gamepad.reports.at(-1).buttons.A, false);
  await waitForState(state, (result) => !result.active);
  assert.equal(gamepad.ready, false);
});

test("tilt racing bindings release the old direction while a pedal stays held", async (t) => {
  const { engine, phone, state } = await setup(t);
  const racing = {
    id: "builtin-tilt-racing",
    name: "Tilt Racing",
    controls: [
      control("TILT", "TILT", { left: key("A"), right: key("D") }),
      control("W", "BUTTON", { press: key("W") }),
      control("S", "BUTTON", { press: key("S") }),
    ],
  };
  const client = await phone(3);
  await client.request("start", 0, { token, outputMode: "keyboard" });
  assert.equal(
    (await client.request("profile", 1, { profile: racing })).type,
    "controller.profile.ready",
  );
  for (const [i, [control, slot, action]] of [
    ["W", "press", "DOWN"],
    ["TILT", "right", "DOWN"],
    ["TILT", "right", "UP"],
    ["TILT", "left", "DOWN"],
    ["TILT", "left", "UP"],
    ["W", "press", "UP"],
    ["S", "press", "DOWN"],
    ["S", "press", "UP"],
  ].entries())
    client.send("input", i + 2, { control, slot, action, revision: 1 });
  await client.request("heartbeat", 10);
  assert.deepEqual(engine.commands.slice(3, -1), [
    "down W",
    "down D",
    "up D",
    "down A",
    "up A",
    "up W",
    "down S",
    "up S",
  ]);
  assert.deepEqual((await state()).controller.held, []);
});

const sampleProfile = {
  id: "custom",
  name: "Custom layout",
  controls: [
    control("jump", "BUTTON", { press: key("SPACE", "SHIFT") }),
    control("jump2", "TRIGGER", { press: key("SPACE", "SHIFT") }),
    control("sneak", "BUTTON", { press: key("SHIFT") }),
    control("stick", "JOYSTICK", { up: key("UP"), left: key("LEFT") }),
    control("pad", "TOUCHPAD", {
      pointer: { type: "POINTER" },
      tap: mouse("LEFT"),
    }),
    control("fire", "BUTTON", { press: mouse("LEFT") }),
    control("aim", "BUTTON", { press: mouse("RIGHT") }),
    control("wheel", "SLIDER", { wheel: { type: "WHEEL" } }),
  ],
};

function input(client, sequence, control, slot, action, revision = 2) {
  client.send("input", sequence, { control, slot, action, revision });
}

const motionOwner = "motion-input-1";
const motionProfile = (motion, controls = []) => ({
  id: "motion-layout",
  name: "Motion layout",
  controls,
  motion: { id: motionOwner, ...motion },
});

test("v4 motion is authenticated and revision gated, and zero restores the touch stick", async (t) => {
  const gamepad = new TestGamepad();
  const { phone, state } = await setup(t, { gamepad });
  const client = await phone(4);
  const axis = (sequence, x, y = 0, revision = 3, control = motionOwner) =>
    client.send("axis", sequence, { control, x, y, revision });
  assert.equal(
    (
      await client.request("start", 0, {
        token: "b".repeat(64),
        outputMode: "gamepad",
      })
    ).code,
    "authorization",
  );
  axis(1, 0.7);
  assert.equal(
    (await client.request("start", 0, { token, outputMode: "gamepad" })).type,
    "controller.ready",
  );
  axis(2, 0.7); // Profile not acknowledged.
  const descriptor = motionProfile(
    { mapping: "STICK", source: "ORIENTATION", stick: "RIGHT_STICK" },
    [
      control("touch", "JOYSTICK", {
        axis: { type: "GAMEPAD_AXIS", target: "RIGHT_STICK" },
      }),
    ],
  );
  assert.equal(
    (await client.request("profile", 3, { profile: descriptor })).type,
    "controller.profile.ready",
  );
  axis(4, 0.5, 0, 2);
  axis(5, 0.9, 0.9);
  axis(6, Infinity);
  axis(7, 1.01);
  axis(8, 0.4, 0.2, 3, "touch");
  axis(9, -0.8, -0.3);
  await client.request("heartbeat", 10, { motionFresh: true });
  const moved = await waitForState(
    state,
    (c) => c.gamepad.commanded?.rightStick.x === -0.8,
  );
  assert.deepEqual(moved.controller.gamepad.commanded.rightStick, {
    x: -0.8,
    y: 0.3,
  });
  assert.equal(moved.controller.motion.last.action, "stick");
  assert.equal(moved.controller.dropped, 7);
  axis(11, 0);
  await client.request("heartbeat", 12, { motionFresh: false });
  const restored = await state();
  assert.deepEqual(restored.controller.gamepad.commanded.rightStick, {
    x: 0.4,
    y: -0.2,
  });
  assert.equal(restored.controller.motion.fresh, false);
  axis(13, 0.7);
  await client.request("heartbeat", 14, { motionFresh: false });
  assert.deepEqual((await state()).controller.gamepad.commanded.rightStick, {
    x: 0.4,
    y: -0.2,
  });
  client.socket.destroy();
  await waitForState(state, (c) => !c.active);
  assert.equal(gamepad.busy, false);
});

test("v4 steering expiry releases only motion ownership while touch and the phone lease survive", async (t) => {
  const { phone, state, engine } = await setup(t);
  const client = await phone(4);
  await client.request("start", 0, { token, outputMode: "keyboard" });
  const profile = motionProfile(
    { mapping: "STEERING", source: "ORIENTATION", leftKey: "A", rightKey: "D" },
    [control("touch", "BUTTON", { press: key("A") })],
  );
  assert.equal((await client.request("profile", 1, { profile })).revision, 1);
  input(client, 2, "touch", "press", "DOWN", 1);
  input(client, 3, motionOwner, "left", "DOWN", 1);
  input(client, 4, motionOwner, "right", "DOWN", 1); // Conflicting steering is invalid.
  await client.request("heartbeat", 5, { motionFresh: true });
  assert.equal((await state()).controller.held.length, 2);
  assert.equal(engine.commands.filter((cmd) => cmd === "down A").length, 1);
  await new Promise((resolve) => setTimeout(resolve, 900));
  const expired = (await state()).controller;
  assert.equal(expired.active, true);
  assert.equal(expired.motion.fresh, false);
  assert.equal(expired.motion.last.action, "neutral");
  assert.equal(expired.held.length, 1);
  assert.equal(engine.commands.includes("up A"), false);
  input(client, 6, "touch", "press", "UP", 1);
  await client.request("heartbeat", 7, { motionFresh: false });
  assert.ok(engine.commands.includes("up A"));
});

test("v4 gyro pointer obeys bounds and never queues motion into a replaced profile", async (t) => {
  const { phone, state, engine } = await setup(t);
  const client = await phone(4);
  await client.request("start", 0, { token, outputMode: "keyboard" });
  const profile = motionProfile({ mapping: "MOUSE", source: "GYROSCOPE" });
  await client.request("profile", 1, { profile });
  client.send("pointer", 2, {
    control: motionOwner,
    revision: 1,
    dx: 7,
    dy: -3,
  });
  client.send("pointer", 3, {
    control: motionOwner,
    revision: 1,
    dx: 513,
    dy: 0,
  });
  client.send("pointer", 4, {
    control: motionOwner,
    revision: 1,
    dx: 0,
    dy: 0,
  });
  await client.request("heartbeat", 5, { motionFresh: true });
  assert.ok(engine.commands.includes("move 7 -3"));
  assert.equal((await state()).controller.motion.last.dx, 7);
  await client.request("profile", 6, { profile });
  client.send("pointer", 7, {
    control: motionOwner,
    revision: 1,
    dx: 10,
    dy: 3,
  });
  await client.request("heartbeat", 8, { motionFresh: false });
  assert.equal(engine.commands.includes("move 10 3"), false);
  assert.equal((await state()).controller.motion.last, null);
  engine.ready = false;
  engine.emit("fault");
  assert.equal((await state()).controller.active, false);
});

test("v2 acknowledges an authenticated mapping and reference-counts keys, modifiers and mouse buttons", async (t) => {
  const { engine, state, phone } = await setup(t);
  const client = await phone(2);
  await client.request("start", 0, { token });
  input(client, 1, "jump", "press", "DOWN");
  assert.deepEqual(
    await client.request("profile", 2, { profile: sampleProfile }),
    {
      version: 2,
      type: "controller.profile.ready",
      sessionId: client.sessionId,
      revision: 2,
    },
  );
  input(client, 3, "jump", "press", "DOWN");
  input(client, 4, "jump2", "press", "DOWN");
  input(client, 5, "sneak", "press", "DOWN");
  input(client, 6, "jump", "press", "UP");
  input(client, 7, "jump2", "press", "UP");
  input(client, 8, "sneak", "press", "UP");
  input(client, 9, "fire", "press", "DOWN");
  input(client, 10, "pad", "tap", "DOWN");
  input(client, 11, "fire", "press", "UP");
  input(client, 12, "pad", "tap", "UP");
  input(client, 13, "aim", "press", "DOWN");
  input(client, 14, "stick", "up", "DOWN");
  input(client, 15, "stick", "left", "DOWN");
  client.send("axis", 16, { control: "stick", x: -0.7, y: -0.7, revision: 2 });
  client.send("pointer", 17, { control: "pad", dx: -31, dy: 9, revision: 2 });
  client.send("wheel", 18, { control: "wheel", ticks: -2, revision: 2 });
  await client.request("heartbeat", 19);
  const { controller } = await state();
  assert.deepEqual(controller.profile, {
    id: "custom",
    name: "Custom layout",
    controls: 8,
  });
  assert.equal(controller.synchronized, true);
  assert.equal(controller.dropped, 1);
  assert.deepEqual(
    controller.held.map(({ key }) => key),
    ["Mouse right", "UP", "LEFT"],
  );
  assert.deepEqual(controller.axis, { control: "stick", x: -0.7, y: -0.7 });
  assert.deepEqual(engine.commands, [
    "alive",
    "reset",
    "alive",
    "down SHIFT",
    "down SPACE",
    "up SPACE",
    "up SHIFT",
    "mouse-down LEFT",
    "mouse-up LEFT",
    "mouse-down RIGHT",
    "down UP",
    "down LEFT",
    "move -31 9",
    "wheel -2",
    "alive",
  ]);
  client.socket.destroy();
  await waitForState(state, (controller) => !controller.active);
  assert.equal(engine.commands.at(-1), "reset");
});

test("replacing or rejecting a v2 profile neutralizes old outputs and rejects stale revisions", async (t) => {
  const { engine, state, phone } = await setup(t);
  const client = await phone(2);
  await client.request("start", 0, { token });
  await client.request("profile", 1, { profile: sampleProfile });
  input(client, 2, "aim", "press", "DOWN", 1);
  const invalid = {
    ...sampleProfile,
    controls: [control("bad", "BUTTON", { press: key("RUN") })],
  };
  const bad = await client.request("profile", 3, { profile: invalid });
  assert.equal(bad.type, "controller.profile.error");
  let current = (await state()).controller;
  assert.match(current.profileError, /Invalid controller profile/);
  assert.equal(current.synchronized, false);
  assert.deepEqual(current.held, []);
  const fresh = await client.request("profile", 4, { profile: sampleProfile });
  assert.equal(fresh.revision, 4);
  input(client, 5, "aim", "press", "DOWN", 1);
  input(client, 6, "aim", "press", "DOWN", 4);
  input(client, 6, "aim", "press", "UP", 4);
  client.send("pointer", 7, { control: "aim", dx: 10, dy: 10, revision: 4 });
  client.send("pointer", 8, { control: "pad", dx: 513, dy: 0, revision: 4 });
  client.send("neutral", 9);
  await client.request("heartbeat", 10);
  current = (await state()).controller;
  assert.equal(current.dropped, 5);
  assert.equal(current.profileError, null);
  assert.deepEqual(current.held, []);
  assert.deepEqual(engine.commands, [
    "alive",
    "reset",
    "alive",
    "mouse-down RIGHT",
    "reset",
    "alive",
    "reset",
    "alive",
    "mouse-down RIGHT",
    "reset",
    "alive",
    "alive",
  ]);
});

test("a bounded large profile is accepted and a frame above the absolute cap is disconnected", async (t) => {
  const { phone, state } = await setup(t);
  const client = await phone(2);
  await client.request("start", 0, { token });
  const longProfile = {
    ...sampleProfile,
    controls: Array.from({ length: 32 }, (_, index) =>
      control(`pad-${index}`, "DPAD", {
        up: key("W", "ALT"),
        down: key("S", "SHIFT"),
        left: key("A"),
        right: key("D"),
      }),
    ),
  };
  assert.ok(Buffer.byteLength(JSON.stringify(longProfile)) > 4096);
  assert.equal(
    (await client.request("profile", 1, { profile: longProfile })).type,
    "controller.profile.ready",
  );
  assert.equal((await state()).controller.profile.controls, 32);
  const closed = once(client.socket, "close");
  client.send("profile", 2, {
    profile: { ...longProfile, padding: "x".repeat(33000) },
  });
  await closed;
  await waitForState(state, (data) => !data.active);
});

test("protocol 5 accepts a large profile and releases input without closing a streaming socket", async (t) => {
  const { engine, phone, state } = await setup(t);
  const client = await phone(5);
  const start = { token, outputMode: "hybrid" };
  const profile = {
    ...hybridProfile(),
    outputMode: "keyboard",
    gyroActivation: null,
    quickSwitch: [],
    motions: [],
    controls: Array.from({ length: 32 }, (_, index) =>
      control(`pad-${index}`, "DPAD", {
        up: key("W", "ALT"),
        down: key("S", "SHIFT"),
        left: key("A"),
        right: key("D"),
      }),
    ),
  };
  assert.ok(Buffer.byteLength(JSON.stringify(profile)) > 4096);
  assert.equal(
    (await client.request("start", 0, start)).type,
    "controller.ready",
  );
  assert.equal(
    (await client.request("profile", 1, { profile }, 521)).type,
    "controller.profile.ready",
  );
  client.send("input", 2, {
    revision: 1,
    control: "pad-0",
    slot: "up",
    action: "DOWN",
  });
  await waitForState(state, ({ held }) => held.length === 1);
  client.send("stop", 3, { keepLink: true });
  await waitForState(
    state,
    ({ active, lastStop }) => !active && lastStop === "Controller stopped",
  );
  assert.equal(engine.commands.at(-1), "reset");
  client.socket.write('{"q":[0,0,0,1],"connectionMode":"usb"}\n');
  assert.equal(
    (await client.request("start", 0, start)).type,
    "controller.ready",
  );
  assert.equal((await client.request("profile", 4, { profile })).revision, 4);
  client.send("input", 5, {
    revision: 1,
    control: "pad-0",
    slot: "up",
    action: "DOWN",
  });
  await client.request("heartbeat", 6, { freshMotion: [] });
  const updated = await state();
  assert.equal(updated.count, 1);
  assert.equal(updated.controller.held.length, 0);
  const closed = once(client.socket, "close");
  client.send("profile", 7, {
    profile: { ...profile, padding: "x".repeat(32768) },
  });
  await closed;
});
