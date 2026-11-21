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
