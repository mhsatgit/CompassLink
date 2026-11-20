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
    phone: async () => {
      const socket = net.createConnection(sensorPort, "127.0.0.1");
      await once(socket, "connect");
      const replies = readline.createInterface({ input: socket });
      const sessionId = randomUUID();
      async function request(type, sequence, fields) {
        const response = once(replies, "line");
        send(type, sequence, fields);
        return JSON.parse((await response)[0]);
      }
      function send(type, sequence, fields) {
        socket.write(
          JSON.stringify({
            version: 1,
            type: `controller.${type}`,
            sessionId,
            sequence,
            ...fields,
          }) + "\n",
        );
      }
      return { socket, send, request };
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
