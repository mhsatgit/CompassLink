const KEYS = new Set(
  require("../android/app/src/main/assets/controller-keys.json"),
);
const MODIFIERS = new Set(["SHIFT", "CTRL", "ALT"]);
const MOUSE_BUTTONS = new Set(["LEFT", "RIGHT", "MIDDLE"]);
const ID = /^[a-zA-Z0-9_-]{1,48}$/;
const DIRECTIONS = Object.freeze({
  up: "hold",
  down: "hold",
  left: "hold",
  right: "hold",
});
const TYPES = Object.freeze({
  BUTTON: { press: "hold" },
  DPAD: DIRECTIONS,
  JOYSTICK: DIRECTIONS,
  TRIGGER: { press: "hold" },
  SLIDER: { press: "hold", wheel: "wheel" },
  TOUCHPAD: { pointer: "pointer", tap: "mouse" },
});

const object = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const name = (value, allowEmpty = false) =>
  typeof value === "string" &&
  value.length <= 48 &&
  (allowEmpty || value.trim().length > 0) &&
  !/[\x00-\x1f\x7f]/u.test(value);

function parseBinding(binding, slot) {
  if (!object(binding)) return null;
  if (slot === "pointer" || slot === "wheel") {
    return binding.type === slot.toUpperCase() ? { type: binding.type } : null;
  }
  if (binding.type === "MOUSE_BUTTON" && MOUSE_BUTTONS.has(binding.button)) {
    return { type: binding.type, button: binding.button };
  }
  if (slot !== "hold" || binding.type !== "KEYBOARD" || !KEYS.has(binding.key))
    return null;
  const modifiers = Object.hasOwn(binding, "modifiers")
    ? binding.modifiers
    : [];
  if (
    !Array.isArray(modifiers) ||
    modifiers.length > 3 ||
    !modifiers.every((item) => MODIFIERS.has(item) && item !== binding.key) ||
    new Set(modifiers).size !== modifiers.length
  )
    return null;
  return {
    type: binding.type,
    key: binding.key,
    modifiers: [...MODIFIERS].filter((key) => modifiers.includes(key)),
  };
}

function validateProfile(profile) {
  if (
    !object(profile) ||
    typeof profile.id !== "string" ||
    !ID.test(profile.id) ||
    !name(profile.name) ||
    !Array.isArray(profile.controls) ||
    profile.controls.length > 32
  )
    return null;

  const controls = new Map();
  for (const control of profile.controls) {
    if (
      !object(control) ||
      typeof control.id !== "string" ||
      !ID.test(control.id) ||
      controls.has(control.id) ||
      !name(control.label, true) ||
      typeof control.type !== "string" ||
      !Object.hasOwn(TYPES, control.type) ||
      !object(control.bindings)
    )
      return null;
    const slots = TYPES[control.type];
    const bindings = new Map();
    for (const [slot, binding] of Object.entries(control.bindings)) {
      const parsed =
        Object.hasOwn(slots, slot) && parseBinding(binding, slots[slot]);
      if (!parsed) return null;
      bindings.set(slot, parsed);
    }
    controls.set(control.id, {
      id: control.id,
      label: control.label,
      type: control.type,
      bindings,
    });
  }
  return { id: profile.id, name: profile.name, controls };
}

function outputsFor(binding) {
  if (binding.type === "KEYBOARD") {
    return [...binding.modifiers, binding.key].map((key) => ({
      kind: "key",
      key,
    }));
  }
  if (binding.type === "MOUSE_BUTTON")
    return [{ kind: "mouse", key: binding.button }];
  return [];
}

function describeBinding(binding) {
  if (binding.type === "KEYBOARD")
    return [...binding.modifiers, binding.key].join(" + ");
  if (binding.type === "MOUSE_BUTTON")
    return `Mouse ${binding.button.toLowerCase()}`;
  return binding.type === "POINTER" ? "Mouse movement" : "Mouse wheel";
}

module.exports = { ID, validateProfile, outputsFor, describeBinding };
