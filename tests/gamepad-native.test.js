const assert = require("node:assert/strict");
const { test } = require("node:test");
const { EventEmitter, on, once } = require("node:events");
const { execFile } = require("node:child_process");
const { promisify } = require("node:util");
const { randomUUID } = require("node:crypto");
const net = require("node:net");
const readline = require("node:readline");
const { setTimeout: delay } = require("node:timers/promises");
const { GamepadState } = require("../pc/gamepad/gamepad-state");
const { WindowsGamepad, BUTTONS } = require("../pc/gamepad/windows-gamepad");
const { createCompassServer } = require("../pc/server");

const native =
  process.platform === "win32" &&
  process.env.COMPASSLINK_NATIVE_GAMEPAD_TEST === "1";
const token = "a".repeat(64);
const exec = promisify(execFile);
const controls = [
  ...Object.keys(BUTTONS).map((target) => ({
    id: target,
    type: "BUTTON",
    label: target,
    bindings: { press: { type: "GAMEPAD", target } },
  })),
  {
    id: "DPAD",
    label: "D-pad",
    type: "DPAD",
    bindings: Object.fromEntries(
      ["up", "down", "left", "right"].map((slot) => [
        slot,
        { type: "GAMEPAD", target: "DPAD_" + slot.toUpperCase() },
      ]),
    ),
  },
  ...["LEFT_STICK", "RIGHT_STICK", "LEFT_TRIGGER", "RIGHT_TRIGGER"].map(
    (target) => ({
      id: target,
      label: target,
      type:
        target === "RIGHT_STICK"
          ? "TOUCHPAD"
          : target === "LEFT_STICK"
            ? "JOYSTICK"
            : "TRIGGER",
      bindings: { axis: { type: "GAMEPAD_AXIS", target } },
    }),
  ),
];

async function waitFor(read, predicate) {
  const expires = performance.now() + 8000;
  let last;
  while (performance.now() < expires) {
    last = await read();
    if (predicate(last)) return last;
    await delay(25);
  }
  assert.fail("Expected native state. Last sample: " + JSON.stringify(last));
}

test(
  "authenticated controller protocol drives real Windows XInput reports",
  { skip: !native, timeout: 40000 },
  async (t) => {
    const engine = Object.assign(new EventEmitter(), {
      start() {},
      close() {},
      send() {
        assert.fail("Gamepad mode used keyboard injection");
      },
    });
    const app = createCompassServer({
      httpPort: 0,
      sensorPort: 0,
      readAuthorization: () => ({ token, mode: "wireless" }),
      engine,
    });
    t.after(() => app.close());
    const { httpPort, sensorPort } = await app.start();
    async function desktop() {
      const response = await fetch(`http://127.0.0.1:${httpPort}/api/state`);
      return (await response.json()).controller.gamepad;
    }
    await waitFor(desktop, (state) => state.installed === true);
    async function phone() {
      const socket = net.createConnection(sensorPort, "127.0.0.1");
      await once(socket, "connect");
      let sequence = 0;
      let revision;
      const sessionId = randomUUID();
      const replies = readline.createInterface({ input: socket });
      const send = (type, fields) =>
        socket.write(
          JSON.stringify({
            version: 3,
            type: "controller." + type,
            sessionId,
            sequence: sequence++,
            revision,
            ...fields,
          }) + "\n",
        );
      async function request(type, fields) {
        const lines = on(replies, "line", {
          signal: AbortSignal.timeout(9000),
        });
        send(type, fields);
        for await (const [line] of lines) {
          const reply = JSON.parse(line);
          if (reply.type !== "controller.heartbeat") return reply;
        }
      }
      assert.equal(
        (await request("start", { token, outputMode: "gamepad" })).type,
        "controller.ready",
      );
      const pulse = setInterval(() => send("heartbeat"), 500);
      socket.on("close", () => clearInterval(pulse));
      t.after(() => clearInterval(pulse));
      const result = await request("profile", {
        profile: { id: "test", name: "Native validation", controls },
      });
      assert.equal(result.type, "controller.profile.ready", result.message);
      revision = result.revision;
      return { socket, send };
    }
    const expectNative = (predicate) =>
      waitFor(desktop, (state) => state.observed && predicate(state.observed));
    const neutral = (state) =>
      state.buttons === 0 &&
      state.leftX === 0 &&
      state.leftY === 0 &&
      state.rightX === 0 &&
      state.rightY === 0 &&
      state.leftTrigger === 0 &&
      state.rightTrigger === 0;
    const client = await phone();
    await expectNative(neutral);
    for (const control of Object.keys(BUTTONS))
      client.send("input", { control, slot: "press", action: "DOWN" });
    for (const slot of ["up", "right"])
      client.send("input", { control: "DPAD", slot, action: "DOWN" });
    client.send("axis", { control: "LEFT_STICK", x: 1, y: -1 });
    client.send("axis", { control: "RIGHT_STICK", x: -1, y: 1 });
    client.send("axis", { control: "LEFT_TRIGGER", x: 0.25, y: 0 });
    client.send("axis", { control: "RIGHT_TRIGGER", x: 0.75, y: 0 });
    const filled = await expectNative(
      (state) =>
        state.buttons === 0xf3f9 &&
        state.leftX === 32767 &&
        state.leftY === 32767 &&
        state.rightX === -32768 &&
        state.rightY === -32768 &&
        state.leftTrigger === 64 &&
        state.rightTrigger === 191,
    );
    t.diagnostic(
      "Windows XInput simultaneous digital and analog: " +
        JSON.stringify(filled.observed),
    );
    for (const control of Object.keys(BUTTONS))
      client.send("input", { control, slot: "press", action: "UP" });
    for (const slot of ["up", "right"])
      client.send("input", { control: "DPAD", slot, action: "UP" });
    for (const slot of ["down", "left"])
      client.send("input", { control: "DPAD", slot, action: "DOWN" });
    await expectNative((state) => state.buttons === 0x0006);
    for (const control of ["LEFT_TRIGGER", "RIGHT_TRIGGER"])
      client.send("axis", { control, x: 1, y: 0 });
    await expectNative(
      (state) => state.leftTrigger === 255 && state.rightTrigger === 255,
    );
    client.send("neutral");
    await expectNative(neutral);
    client.send("input", { control: "A", slot: "press", action: "DOWN" });
    client.send("axis", { control: "LEFT_STICK", x: 1, y: 0 });
    await expectNative(
      (state) => state.buttons === 0x1000 && state.leftX === 32767,
    );
    client.socket.destroy();
    await waitFor(
      desktop,
      (state) =>
        state.slot === null && ["disconnected", "ready"].includes(state.status),
    );
    const replacement = await phone();
    const reconnected = await expectNative(neutral);
    t.diagnostic(
      `XInput slot ${reconnected.slot} reconnected in neutral state after held A and stick disconnection.`,
    );
    replacement.socket.destroy();
  },
);

test(
  "native helper lease expires and allows a clean reconnect",
  { skip: !native, timeout: 25000 },
  async (t) => {
    const adapter = new WindowsGamepad();
    t.after(() => adapter.close());
    await adapter.connect();
    const state = new GamepadState();
    state.setButton("lease", "B", true);
    assert.equal(adapter.submit(state.snapshot()), true);
    await waitFor(
      () => adapter.snapshot(),
      (sample) => sample.observed?.buttons === 0x2000,
    );
    const [fault] = await once(adapter, "fault", {
      signal: AbortSignal.timeout(6000),
    });
    assert.match(fault.message, /lease expired/i);
    assert.equal(adapter.snapshot().slot, null);
    await adapter.connect();
    await waitFor(
      () => adapter.snapshot(),
      (sample) => sample.observed?.buttons === 0,
    );
    await adapter.disconnect();
    assert.equal(adapter.snapshot().status, "disconnected");
  },
);

test(
  "native HID keyboard and Xbox remain independent and switch neutral on the same XInput slot",
  { skip: !native, timeout: 50000 },
  async (t) => {
    const app = createCompassServer({
      httpPort: 0,
      sensorPort: 0,
      readAuthorization: () => ({ token, mode: "wireless" }),
    });
    t.after(() => app.close());
    const { httpPort, sensorPort } = await app.start();
    const desktop = async () =>
      (await (await fetch(`http://127.0.0.1:${httpPort}/api/state`)).json())
        .controller;
    await waitFor(desktop, (sample) => sample.engineReady);
    // Read the real Windows keyboard state independently of the server's ownership model.
    const key = async () => {
      const { stdout } = await exec(
        "powershell.exe",
        [
          "-NoProfile",
          "-NonInteractive",
          "-Command",
          "Add-Type -Name Keys -Namespace CompassLink -MemberDefinition '[System.Runtime.InteropServices.DllImport(\"user32.dll\")] public static extern short GetAsyncKeyState(int key);'; " +
            "([CompassLink.Keys]::GetAsyncKeyState(0x78) -band 0x8000) -ne 0",
        ],
        { windowsHide: true, timeout: 3500 },
      );
      return stdout.trim() === "True";
    };
    assert.equal(await key(), false, "F9 should be free before testing");
    const socket = net.createConnection(sensorPort, "127.0.0.1");
    await once(socket, "connect");
    t.after(() => socket.destroy());
    const replies = readline.createInterface({ input: socket });
    const sessionId = randomUUID();
    let sequence = 0;
    let revision;
    function send(type, fields) {
      socket.write(
        JSON.stringify({
          version: 5,
          sessionId,
          type: `controller.${type}`,
          sequence: sequence++,
          revision,
          ...fields,
        }) + "\n",
      );
    }
    async function request(type, fields) {
      const lines = on(replies, "line", { signal: AbortSignal.timeout(9000) });
      send(type, fields);
      for await (const [line] of lines) {
        const reply = JSON.parse(line);
        if (reply.type !== "controller.heartbeat") return reply;
      }
    }
    const profile = {
      id: "native-hybrid",
      name: "Hybrid native check",
      outputMode: "hybrid",
      orientationPolicy: "LANDSCAPE",
      haptics: "OFF",
      motions: [],
      quickSwitch: [],
      gyroActivation: null,
      controls: [
        ...controls.filter(({ id }) => ["RB", "LEFT_STICK"].includes(id)),
        {
          id: "key",
          label: "F9",
          type: "BUTTON",
          bindings: { press: { type: "KEYBOARD", key: "F9" } },
        },
      ],
    };
    assert.equal(
      (await request("start", { token, outputMode: "hybrid" })).type,
      "controller.ready",
    );
    const pulse = setInterval(
      () => send("heartbeat", { freshMotion: [] }),
      500,
    );
    t.after(() => clearInterval(pulse));
    const first = await request("profile", { profile });
    assert.equal(first.type, "controller.profile.ready", first.message);
    revision = first.revision;
    const slot = (await desktop()).gamepad.slot;
    send("axis", { control: "LEFT_STICK", x: 0.75, y: 0 });
    await waitFor(desktop, ({ gamepad }) => gamepad.observed?.leftX > 20000);
    send("input", { control: "key", slot: "press", action: "DOWN" });
    await waitFor(key, Boolean);
    const mixed = await desktop();
    assert.match(mixed.engineMessage, /Virtual USB HID Keyboard/);
    assert.ok(mixed.gamepad.observed.leftX > 20000);
    send("input", { control: "RB", slot: "press", action: "DOWN" });
    await waitFor(
      desktop,
      ({ gamepad }) => gamepad.observed?.buttons === 0x200,
    );
    assert.equal(await key(), true);
    const oldRevision = revision;
    const second = await request("profile", {
      profile: { ...profile, id: "native-resync" },
    });
    assert.equal(second.type, "controller.profile.ready", second.message);
    revision = second.revision;
    send("input", {
      control: "key",
      slot: "press",
      action: "DOWN",
      revision: oldRevision,
    });
    send("axis", {
      control: "LEFT_STICK",
      x: 0.5,
      y: 0,
      revision: oldRevision,
    });
    await waitFor(
      desktop,
      ({ gamepad }) =>
        gamepad.observed?.buttons === 0 && gamepad.observed?.leftX === 0,
    );
    assert.equal(await key(), false);
    assert.equal((await desktop()).gamepad.slot, slot);
    send("input", { control: "key", slot: "press", action: "DOWN" });
    await waitFor(key, Boolean);
    socket.destroy();
    clearInterval(pulse);
    await waitFor(
      desktop,
      (sample) => !sample.active && sample.gamepad.slot === null,
    );
    assert.equal(await key(), false);
  },
);
