const assert = require("node:assert/strict");
const { test } = require("node:test");
const { GamepadState } = require("../pc/gamepad/gamepad-state");

test("gamepad starts neutral and repeated reset clears every input", () => {
  const state = new GamepadState();
  const neutral = state.snapshot();
  assert.deepEqual(neutral, {
    buttons: {
      A: false,
      B: false,
      X: false,
      Y: false,
      LB: false,
      RB: false,
      START: false,
      BACK: false,
      L3: false,
      R3: false,
    },
    dpad: { up: false, down: false, left: false, right: false },
    leftStick: { x: 0, y: 0 },
    rightStick: { x: 0, y: 0 },
    leftTrigger: 0,
    rightTrigger: 0,
  });
  for (const name of Object.keys(neutral.buttons))
    assert(state.setButton(name, name, true));
  for (const name of Object.keys(neutral.buttons))
    assert.equal(state.snapshot().buttons[name], true);
  state.setButton("direction", "DPAD_UP", true);
  state.setAxis("joystick", "RIGHT_STICK", 0.25, 0.5);
  state.setAxis("trigger", "LEFT_TRIGGER", 0.75);
  state.reset();
  state.reset();
  assert.deepEqual(state.snapshot(), neutral);
});

test("overlapping digital sources release only their own output ownership", () => {
  const state = new GamepadState();
  assert.equal(state.setButton("one:press", "A", false), false);
  assert(state.setButton("one:press", "A", true));
  assert.equal(state.setButton("one:press", "A", true), false);
  assert(state.setButton("two:press", "A", true));
  assert.equal(state.setButton("two:press", "B", false), false);
  assert(state.setButton("one:press", "A", false));
  assert.equal(state.snapshot().buttons.A, true);
  assert(state.setButton("two:press", "A", false));
  assert.equal(state.snapshot().buttons.A, false);
});

test("D-pad supports diagonals and opposing directions cancel per axis", () => {
  const state = new GamepadState();
  state.setButton("first:up", "DPAD_UP", true);
  state.setButton("first:right", "DPAD_RIGHT", true);
  assert.deepEqual(state.snapshot().dpad, {
    up: true,
    down: false,
    left: false,
    right: true,
  });
  state.setButton("second:down", "DPAD_DOWN", true);
  assert.deepEqual(state.snapshot().dpad, {
    up: false,
    down: false,
    left: false,
    right: true,
  });
  state.setButton("first:up", "DPAD_UP", false);
  assert.deepEqual(state.snapshot().dpad, {
    up: false,
    down: true,
    left: false,
    right: true,
  });
});

test("both sticks and triggers are independent, bounded, and return to zero", () => {
  const state = new GamepadState();
  state.setAxis("movement", "LEFT_STICK", -2, 1.5);
  state.setAxis("aim", "RIGHT_STICK", 0.25, -0.75);
  state.setAxis("left", "LEFT_TRIGGER", 0.4);
  state.setAxis("right", "RIGHT_TRIGGER", 1.5);
  assert.deepEqual(state.snapshot().leftStick, { x: -1, y: 1 });
  assert.deepEqual(state.snapshot().rightStick, { x: 0.25, y: -0.75 });
  assert.equal(state.snapshot().leftTrigger, 0.4);
  assert.equal(state.snapshot().rightTrigger, 1);
  state.setAxis("movement", "LEFT_STICK", 0, 0);
  state.setAxis("left", "LEFT_TRIGGER", -1);
  assert.deepEqual(state.snapshot().leftStick, { x: 0, y: 0 });
  assert.deepEqual(state.snapshot().rightStick, { x: 0.25, y: -0.75 });
  assert.equal(state.snapshot().leftTrigger, 0);
  assert.equal(state.snapshot().rightTrigger, 1);
});

test("most recent non-neutral analog source wins and a release restores the preceding source", () => {
  const state = new GamepadState();
  state.setAxis("left-1", "LEFT_STICK", -0.5, 0.5);
  state.setAxis("left-2", "LEFT_STICK", 0.5, 0);
  assert.deepEqual(state.snapshot().leftStick, { x: 0.5, y: 0 });
  state.setAxis("left-1", "LEFT_STICK", -0.5, 0.5);
  assert.deepEqual(state.snapshot().leftStick, { x: -0.5, y: 0.5 });
  state.setAxis("left-1", "LEFT_STICK", 0, 0);
  assert.deepEqual(state.snapshot().leftStick, { x: 0.5, y: 0 });
  state.setAxis("left-2", "LEFT_STICK", 0, 0);
  assert.deepEqual(state.snapshot().leftStick, { x: 0, y: 0 });
});

test("invalid input and conflicting owner assignments leave the previous report intact", () => {
  const state = new GamepadState();
  state.setAxis("owner", "LEFT_TRIGGER", 0.6);
  const original = state.snapshot();
  assert.equal(state.setAxis("owner", "LEFT_TRIGGER", Number.NaN), false);
  assert.equal(
    state.setAxis("owner", "LEFT_TRIGGER", Number.POSITIVE_INFINITY),
    false,
  );
  assert.equal(state.setAxis("owner", "LEFT_TRIGGER", 0.6, 1), false);
  assert.equal(state.setAxis("owner", "RIGHT_TRIGGER", 0.4), false);
  assert.equal(state.setButton("owner", "LEFT_TRIGGER", true), false);
  assert.equal(state.setButton("invalid\nowner", "A", true), false);
  assert.equal(state.setButton("owner", "A", 1), false);
  assert.deepEqual(state.snapshot(), original);
});
