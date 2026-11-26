const assert = require("node:assert/strict");
const { test } = require("node:test");
const { validateProfile } = require("../pc/controller-profile");

function profile(binding) {
  return {
    id: "example_2",
    name: "Wireless layout",
    controls: [
      {
        id: "action",
        label: "Sprint",
        type: "BUTTON",
        bindings: { press: binding },
      },
    ],
  };
}

test("profile validator accepts and canonicalizes supported keys and modifiers", () => {
  const keys = require("../android/app/src/main/assets/controller-keys.json");
  for (const key of keys) {
    const parsed = validateProfile(
      profile({ type: "KEYBOARD", key, modifiers: [] }),
    );
    assert.equal(parsed.controls.get("action").bindings.get("press").key, key);
  }
  assert.deepEqual(
    validateProfile(
      profile({ type: "KEYBOARD", key: "R", modifiers: ["ALT", "SHIFT"] }),
    )
      .controls.get("action")
      .bindings.get("press").modifiers,
    ["SHIFT", "ALT"],
  );
});

test("profile validator rejects malformed bindings and escaping command data", () => {
  for (const invalid of [
    { type: "KEYBOARD", key: "W\nreset" },
    { type: "KEYBOARD", key: 0x57 },
    { type: "KEYBOARD", key: "W", modifiers: "CTRL" },
    { type: "KEYBOARD", key: "SHIFT", modifiers: ["SHIFT"] },
    { type: "KEYBOARD", key: "W", modifiers: ["CTRL", "CTRL"] },
    { type: "KEYBOARD", key: "W", modifiers: ["META"] },
    { type: "MOUSE_BUTTON", button: "X1" },
    { type: "POINTER" },
    { type: "WHEEL" },
    { type: "GAMEPAD", target: "A" },
    null,
  ])
    assert.equal(validateProfile(profile(invalid)), null);
});

test("profile validator bounds names and counts and requires unique, valid identifiers", () => {
  const valid = profile({ type: "KEYBOARD", key: "Q" });
  for (const invalid of [
    { ...valid, id: "../profile" },
    { ...valid, name: " " },
    { ...valid, name: "Q".repeat(49) },
    { ...valid, name: "line\nbreak" },
    { ...valid, controls: [valid.controls[0], valid.controls[0]] },
    { ...valid, controls: Array(33).fill(valid.controls[0]) },
    { ...valid, controls: [{ ...valid.controls[0], type: "constructor" }] },
    {
      ...valid,
      controls: [
        {
          ...valid.controls[0],
          bindings: { up: { type: "KEYBOARD", key: "W" } },
        },
      ],
    },
  ])
    assert.equal(validateProfile(invalid), null);
});

test("protocol 4 validates a separate motion owner and target for the selected output mode", () => {
  const value = {
    id: "layout",
    name: "Motion layout",
    controls: [],
    motion: {
      id: "motion-input-1",
      mapping: "STICK",
      source: "ORIENTATION",
      stick: "LEFT_STICK",
    },
  };
  const gamepad = validateProfile(value, "gamepad", 4);
  assert.equal(gamepad.motion.bindings.get("axis").target, "LEFT_STICK");
  assert.equal(gamepad.controls.get("motion-input-1"), gamepad.motion);
  assert.equal(validateProfile(value, "gamepad", 3), null);
  assert.equal(validateProfile(value, "keyboard", 4), null);
  assert.equal(validateProfile({ ...value, motion: null }, "gamepad", 4), null);
  for (const motion of [
    { ...value.motion, stick: "LEFT_TRIGGER" },
    { ...value.motion, source: "OTHER" },
    { ...value.motion, rate: Infinity },
    { ...value.motion, id: 3 },
  ])
    assert.equal(validateProfile({ ...value, motion }, "gamepad", 4), null);
  const collision = {
    id: value.motion.id,
    label: "Existing",
    type: "BUTTON",
    bindings: {},
  };
  assert.equal(
    validateProfile({ ...value, controls: [collision] }, "gamepad", 4),
    null,
  );
  const steer = {
    id: "steer",
    mapping: "STEERING",
    source: "ORIENTATION",
    leftKey: "A",
    rightKey: "D",
  };
  assert.equal(
    validateProfile(
      { ...value, motion: steer },
      "keyboard",
      4,
    ).motion.bindings.get("right").key,
    "D",
  );
  assert.equal(
    validateProfile(
      { ...value, motion: { ...steer, leftKey: "D" } },
      "keyboard",
      4,
    ),
    null,
  );
  assert.equal(
    validateProfile(
      { ...value, motion: { ...steer, source: "GYROSCOPE" } },
      "keyboard",
      4,
    ),
    null,
  );
});
