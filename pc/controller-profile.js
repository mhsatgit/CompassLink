const { DIGITAL, STICKS, TRIGGERS } = require("./gamepad/gamepad-state");
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
  TILT: { left: "hold", right: "hold" },
  PROFILE_SWITCH: {},
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

function gamepadBinding(binding, slot, type) {
  if (!object(binding)) return null;
  const { target } = binding;
  if (slot === "axis") {
    const validTarget =
      type === "TOUCHPAD"
        ? target === "RIGHT_STICK"
        : (type === "JOYSTICK" ? STICKS : TRIGGERS).has(target);
    if (binding.type !== "GAMEPAD_AXIS" || !validTarget) return null;
  } else if (
    !["hold", "mouse"].includes(slot) ||
    binding.type !== "GAMEPAD" ||
    !DIGITAL.has(target)
  )
    return null;
  return { type: binding.type, target };
}

function motionControl(value, mode, controls) {
  if (
    !object(value) ||
    typeof value.id !== "string" ||
    !ID.test(value.id) ||
    controls.has(value.id) ||
    !["ORIENTATION", "GYROSCOPE"].includes(value.source)
  )
    return null;
  const { id, mapping, source, stick, leftKey, rightKey } = value;
  const bindings = new Map();
  let type;
  let fields = 3;
  if (mapping === "STICK" && mode !== "keyboard" && STICKS.has(stick)) {
    type = "JOYSTICK";
    bindings.set("axis", { type: "GAMEPAD_AXIS", target: stick });
    fields++;
  } else if (mapping === "MOUSE" && mode !== "gamepad") {
    type = "TOUCHPAD";
    bindings.set("pointer", { type: "POINTER" });
  } else if (
    mapping === "STEERING" &&
    mode !== "gamepad" &&
    source === "ORIENTATION" &&
    leftKey !== rightKey &&
    KEYS.has(leftKey) &&
    KEYS.has(rightKey)
  ) {
    type = "TILT";
    bindings.set("left", { type: "KEYBOARD", key: leftKey, modifiers: [] });
    bindings.set("right", { type: "KEYBOARD", key: rightKey, modifiers: [] });
    fields += 2;
  } else return null;
  if (Object.keys(value).length !== fields) return null;
  return { id, label: "Motion", type, bindings, mapping, source };
}

function validateProfile(profile, mode = "keyboard", version = 3) {
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
      (version < 5 && control.type === "PROFILE_SWITCH") ||
      !object(control.bindings)
    )
      return null;
    const slots = TYPES[control.type];
    const bindings = new Map();
    for (const [slot, binding] of Object.entries(control.bindings)) {
      const kind = Object.hasOwn(slots, slot) ? slots[slot] : null;
      const analog =
        slot === "axis" &&
        ["JOYSTICK", "TRIGGER", "SLIDER", "TOUCHPAD"].includes(control.type);
      const parsed =
        mode !== "keyboard" &&
        (binding?.type === "GAMEPAD" || binding?.type === "GAMEPAD_AXIS")
          ? gamepadBinding(binding, analog ? "axis" : kind, control.type)
          : mode !== "gamepad" && kind && parseBinding(binding, kind);
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
  if (version === 5) return hybridProfile(profile, mode, controls);
  const hasMotion = Object.hasOwn(profile, "motion");
  if (hasMotion !== (version === 4)) return null;
  const motion = hasMotion
    ? motionControl(profile.motion, mode, controls)
    : null;
  if (hasMotion && !motion) return null;
  if (motion) controls.set(motion.id, motion);
  return {
    id: profile.id,
    name: profile.name,
    controls,
    motion,
    motions: motion ? [motion] : [],
    mode,
  };
}

function hybridProfile(profile, mode, controls) {
  const { motions, orientationPolicy, haptics, gyroActivation, quickSwitch } =
    profile;
  if (
    Object.keys(profile).some(
      (field) =>
        ![
          "id",
          "name",
          "controls",
          "outputMode",
          "orientationPolicy",
          "haptics",
          "gyroActivation",
          "quickSwitch",
          "motions",
        ].includes(field),
    ) ||
    profile.outputMode !== mode ||
    !["keyboard", "gamepad", "hybrid"].includes(mode) ||
    !["SYSTEM", "PORTRAIT", "LANDSCAPE"].includes(orientationPolicy) ||
    !["OFF", "LIGHT", "MEDIUM", "STRONG"].includes(haptics) ||
    !Array.isArray(motions) ||
    motions.length > 2 ||
    !Array.isArray(quickSwitch) ||
    quickSwitch.length > 32
  )
    return null;
  const parsed = [];
  for (const item of motions) {
    const motion = motionControl(item, mode, controls);
    if (!motion || parsed.some(({ source }) => source === motion.source))
      return null;
    controls.set(motion.id, motion);
    parsed.push(motion);
  }
  if (parsed.some(({ source }) => source === "GYROSCOPE")) {
    if (
      !object(gyroActivation) ||
      !["HOLD", "TOGGLE", "ALWAYS"].includes(gyroActivation.behavior)
    )
      return null;
    if (gyroActivation.source === "CONTROL") {
      const { control, slot } = gyroActivation;
      const source = controls.get(control);
      const binding = source?.bindings.get(slot);
      if (
        Object.keys(gyroActivation).length !== 4 ||
        gyroActivation.behavior === "ALWAYS" ||
        !source ||
        !["BUTTON", "DPAD", "JOYSTICK", "TRIGGER", "SLIDER"].includes(
          source.type,
        ) ||
        !["press", "up", "down", "left", "right"].includes(slot) ||
        !binding ||
        !["KEYBOARD", "MOUSE_BUTTON", "GAMEPAD"].includes(binding.type)
      )
        return null;
    } else if (
      gyroActivation.source !== "DEDICATED" ||
      Object.keys(gyroActivation).length !== 2
    )
      return null;
  } else if (gyroActivation !== null) return null;
  const seen = new Set();
  for (const reference of quickSwitch) {
    if (
      !object(reference) ||
      Object.keys(reference).length !== 2 ||
      controls.get(reference.control)?.type !== "PROFILE_SWITCH" ||
      typeof reference.target !== "string" ||
      !ID.test(reference.target) ||
      reference.target === profile.id ||
      seen.has(reference.control)
    )
      return null;
    seen.add(reference.control);
  }
  if (
    [...controls.values()].some(
      ({ id, type }) => type === "PROFILE_SWITCH" && !seen.has(id),
    )
  )
    return null;
  return {
    id: profile.id,
    name: profile.name,
    controls,
    mode,
    motions: parsed,
    motion: parsed[0] ?? null,
    orientationPolicy,
    haptics,
    gyroActivation,
    quickSwitch,
  };
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
  if (binding.type === "GAMEPAD" || binding.type === "GAMEPAD_AXIS")
    return binding.target.replaceAll("_", " ");
  if (binding.type === "KEYBOARD")
    return [...binding.modifiers, binding.key].join(" + ");
  if (binding.type === "MOUSE_BUTTON")
    return `Mouse ${binding.button.toLowerCase()}`;
  return binding.type === "POINTER" ? "Mouse movement" : "Mouse wheel";
}

module.exports = { ID, validateProfile, outputsFor, describeBinding };
