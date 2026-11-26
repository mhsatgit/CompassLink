const { test } = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");
const http = require("node:http");
const { once } = require("node:events");
const readline = require("node:readline");
const { createCompassServer, parsePacket } = require("../pc/server.js");
const { WindowsGamepad } = require("../pc/gamepad/windows-gamepad");

test("reject malformed sensor data and normalize valid quaternions", () => {
  for (const line of [
    "null",
    "{}",
    '{"q":[0,0,0,0]}',
    '{"q":[1,2,3]}',
    '{"q":["x",0,0,1]}',
    '{"q":[1e309,0,0,1]}',
  ]) {
    assert.throws(() => parsePacket(line));
  }
  assert.deepEqual(parsePacket('{"q":[0,0,0,2]}').q, [0, 0, 0, 1]);
});

function diagnostics() {
  const sensor = (available, active, hz) => ({ available, active, hz });
  return {
    orientation: "gameRotation",
    sensors: {
      gameRotation: sensor(true, true, 47.5),
      rotation: sensor(true, false, null),
      gyroscope: sensor(true, true, 49.1),
      accelerometer: sensor(true, true, 48.9),
    },
    relative: [0, 0, 0, 1],
    centerAgeMs: 2160,
    gyro: [0.2, -0.4, 0.3],
    accel: [0, 0, 9.81],
    mapping: "MOUSE",
    source: "GYROSCOPE",
    target: "POINTER",
    gyroEnabled: true,
    stale: false,
    invalid: 2,
    output: [0.3, -0.4],
  };
}

test("link check confirms the desktop receiver without opening an input session", async () => {
  const app = createCompassServer({ httpPort: 0, sensorPort: 0 });
  const ports = await app.start();
  const socket = net.createConnection({
    host: "127.0.0.1",
    port: ports.sensorPort,
  });
  try {
    await once(socket, "connect");
    socket.write('{"type":"link.check","version":1}\n');
    let line = "";
    for await (const part of socket) line += part;
    assert.deepEqual(JSON.parse(line), { type: "link.ready", version: 1 });
    const state = await (
      await fetch(`http://127.0.0.1:${ports.httpPort}/api/state`)
    ).json();
    assert.equal(state.controller.active, false);
    assert.equal(state.count, 0);
  } finally {
    socket.destroy();
    await app.close();
  }
});

test("dashboard binding capture is same-origin, one-shot, authenticated, and does not emit input", async (t) => {
  const token = "a".repeat(64);
  let authorization = { token, mode: "usb" };
  const app = createCompassServer({
    httpPort: 0,
    sensorPort: 0,
    readAuthorization: () => authorization,
    gamepad: new WindowsGamepad({ supported: false }),
  });
  const { httpPort, sensorPort } = await app.start();
  t.after(() => app.close());
  const origin = `http://127.0.0.1:${httpPort}`;
  const post = (code, binding, from = origin) =>
    fetch(`${origin}/input-capture`, {
      method: "POST",
      headers: { Origin: from, "Content-Type": "application/json" },
      body: JSON.stringify({ code, binding }),
    });
  const open = async (grant, code) => {
    const socket = net.createConnection(sensorPort, "127.0.0.1");
    socket.on("error", () => {});
    await once(socket, "connect");
    const iterator = readline
      .createInterface({ input: socket })
      [Symbol.asyncIterator]();
    socket.write(
      JSON.stringify({
        type: "capture.listen",
        version: 1,
        mode: "usb",
        token: grant,
        code,
      }) + "\n",
    );
    return {
      socket,
      next: async () => JSON.parse((await iterator.next()).value),
    };
  };
  const code = "1234ABCD";
  const invalid = await open("b".repeat(64), code);
  assert.equal((await invalid.next()).type, "capture.unavailable");
  const phone = await open(token, code);
  assert.equal((await phone.next()).type, "capture.ready");
  const binding = { type: "KEYBOARD", key: "A", modifiers: ["CTRL"] };
  assert.equal(
    (await post(code, binding, "http://example.invalid")).status,
    403,
  );
  assert.equal((await post("00000000", binding)).status, 404);
  assert.equal(
    (await post(code, { ...binding, key: "UNSUPPORTED" })).status,
    400,
  );
  assert.equal((await post(code, binding)).status, 204);
  assert.deepEqual(await phone.next(), { type: "capture.binding", binding });
  assert.equal((await post(code, binding)).status, 404);
  const expired = await open(token, code);
  assert.equal((await expired.next()).type, "capture.ready");
  authorization = { token: "c".repeat(64), mode: "usb" };
  assert.equal((await post(code, binding)).status, 404);
  expired.socket.destroy();
  const state = await (await fetch(`${origin}/api/state`)).json();
  assert.equal(state.controller.active, false);
  assert.ok(!JSON.stringify(state).includes(token));
});

test("orientation and armed secondary gyro arrive as independent mappings", () => {
  const gyro = diagnostics();
  const tilt = {
    ...gyro,
    source: "ORIENTATION",
    mapping: "STICK",
    target: "LEFT_STICK",
    gyroEnabled: false,
  };
  const parse = (mappings) =>
    parsePacket(JSON.stringify({ q: null, motion: { ...tilt, mappings } }))
      .motion;
  assert.deepEqual(
    parse([tilt, gyro]).mappings.map((m) => [m.target, m.gyroEnabled]),
    [
      ["LEFT_STICK", false],
      ["POINTER", true],
    ],
  );
  assert.throws(() => parse([tilt, tilt]));
  assert.throws(() => parse([gyro]));
  assert.throws(() => parse([tilt, { ...gyro, output: [10, 0] }]));
});

test("motion diagnostics are bounded and remain display-only", async () => {
  const motion = diagnostics();
  for (const invalid of [
    { ...motion, output: [0.9, 0.9] },
    { ...motion, gyro: [Infinity, 0, 0] },
    { ...motion, accel: [0, 0, 1001] },
    { ...motion, centerAgeMs: -1 },
    { ...motion, relative: [0, 0, 0, 0] },
    { ...motion, target: "INVALID" },
    { ...motion, orientation: "rotation" },
    {
      ...motion,
      sensors: {
        ...motion.sensors,
        gyroscope: { available: true, active: false, hz: 50 },
      },
    },
  ])
    assert.throws(() =>
      parsePacket(JSON.stringify({ q: null, motion: invalid })),
    );
  const app = createCompassServer({
    httpPort: 0,
    sensorPort: 0,
    gamepad: new WindowsGamepad({ supported: false }),
  });
  const ports = await app.start();
  const socket = net.createConnection({
    host: "127.0.0.1",
    port: ports.sensorPort,
  });
  try {
    await once(socket, "connect");
    const grant = "private-value";
    socket.write(
      JSON.stringify({ q: null, motion: { ...motion, grant }, token: grant }) +
        "\n",
    );
    let data;
    for (let i = 0; i < 20; i++) {
      data = await (
        await fetch(`http://127.0.0.1:${ports.httpPort}/api/state`)
      ).json();
      if (data.motion) break;
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(data.motion.sensors.gameRotation.hz, 47.5);
    assert.equal(data.count, 0);
    assert.equal(data.last, null);
    assert.equal(data.stale, true);
    assert.equal(data.motion.stale, false);
    assert.equal(data.motion.gyroEnabled, true);
    assert.equal(data.controller.active, false);
    assert.equal(JSON.stringify(data).includes(grant), false);
    const closed = once(socket, "close");
    socket.write(
      JSON.stringify({ q: null, motion, padding: "x".repeat(4100) }) + "\n",
    );
    await closed;
  } finally {
    socket.destroy();
    await app.close();
  }
});

test("local TCP samples reach dashboard API with framing, validation and disconnect state", async () => {
  const app = createCompassServer({
    httpPort: 0,
    sensorPort: 0,
    gamepad: new WindowsGamepad({ supported: false }),
  });
  const ports = await app.start();
  const base = `http://127.0.0.1:${ports.httpPort}`;
  function request(options) {
    return new Promise((resolve, reject) => {
      http
        .get(base, options, (response) => {
          response.resume();
          resolve(response);
        })
        .on("error", reject);
    });
  }
  const socket = net.createConnection({
    host: "127.0.0.1",
    port: ports.sensorPort,
  });
  try {
    await once(socket, "connect");
    socket.write('invalid\n{"q":[0,0,');
    socket.write('0,2],"sensor":"test","accuracy":3}\n');
    let state;
    for (let i = 0; i < 30; i++) {
      state = await (await fetch(`${base}/api/state`)).json();
      if (state.count === 1) break;
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(state.count, 1);
    assert.equal(state.connected, true);
    assert.equal(state.stale, false);
    assert.deepEqual(state.last.q, [0, 0, 0, 1]);
    for (const asset of [
      "/",
      "/styles.css",
      "/dashboard.js",
      "/orientation.js",
    ]) {
      assert.equal((await fetch(base + asset)).status, 200);
    }
    assert.equal((await fetch(`${base}/server.js`)).status, 404);
    assert.equal(
      (await request({ headers: { Host: "other.example" } })).statusCode,
      400,
    );
    assert.equal((await request({ path: "//[" })).statusCode, 404);
    const api = await fetch(`${base}/api/state?check=1`);
    assert.equal(api.status, 200);
    assert.equal(api.headers.get("cache-control"), "no-store");
    assert.equal(api.headers.get("x-content-type-options"), "nosniff");
    assert.match(
      api.headers.get("content-security-policy"),
      /frame-ancestors 'none'/,
    );
    const mutation = await fetch(`${base}/api/state`, { method: "POST" });
    assert.equal(mutation.status, 405);
    assert.equal(mutation.headers.get("allow"), "GET");
    socket.end();
    await once(socket, "close");
    for (let i = 0; i < 30; i++) {
      state = await (await fetch(`${base}/api/state`)).json();
      if (!state.connected) break;
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(state.connected, false);
    const noisy = net.createConnection({
      host: "127.0.0.1",
      port: ports.sensorPort,
    });
    try {
      await once(noisy, "connect");
      const closed = once(noisy, "close");
      noisy.write("{}\n".repeat(700));
      await closed;
      assert.equal((await fetch(`${base}/api/state`)).status, 200);
    } finally {
      noisy.destroy();
    }
  } finally {
    socket.destroy();
    await app.close();
  }
});
