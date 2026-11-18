const { test } = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");
const { once } = require("node:events");
const { createCompassServer, parsePacket } = require("../pc/server.js");

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

test("local TCP samples reach dashboard API with framing, validation and disconnect state", async () => {
  const app = createCompassServer({ httpPort: 0, sensorPort: 0 });
  const ports = await app.start();
  const base = `http://127.0.0.1:${ports.httpPort}`;
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
    socket.end();
    await once(socket, "close");
    for (let i = 0; i < 30; i++) {
      state = await (await fetch(`${base}/api/state`)).json();
      if (!state.connected) break;
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(state.connected, false);
  } finally {
    socket.destroy();
    await app.close();
  }
});
