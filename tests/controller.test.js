const { test } = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter, once } = require("node:events");
const { randomUUID } = require("node:crypto");
const net = require("node:net");
const readline = require("node:readline");
const { createCompassServer } = require("../pc/server");
const { CONTROLLER_KEYS } = require("../pc/controller");

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

const token = "a".repeat(64);

async function setup(t, options = {}) {
  const engine = new TestEngine();
  let authorization = { token, mode: "usb" };
  const app = createCompassServer({
    httpPort: 0,
    sensorPort: 0,
    engine,
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
      async function request(type, sequence, fields) {
        const response = once(replies, "line", {
          signal: AbortSignal.timeout(2000),
        });
        send(type, sequence, fields);
        return JSON.parse((await response)[0]);
      }
      function send(type, sequence, fields) {
        socket.write(
          JSON.stringify({
            version,
            type: `controller.${type}`,
            sessionId,
            sequence,
            ...fields,
          }) + "\n",
        );
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
  assert.equal(
    (await client.request("start", 0, { token: "b".repeat(64) })).type,
    "controller.error",
  );
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
  assert.equal((await first.request("heartbeat", 3)).type, "controller.error");
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

const key = (key, ...modifiers) => ({ type: "KEYBOARD", key, modifiers });
const mouse = (button) => ({ type: "MOUSE_BUTTON", button });
const control = (id, type, bindings) => ({ id, type, label: id, bindings });
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
