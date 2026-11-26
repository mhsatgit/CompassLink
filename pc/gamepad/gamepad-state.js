const BUTTONS = Object.freeze([
  "A",
  "B",
  "X",
  "Y",
  "LB",
  "RB",
  "START",
  "BACK",
  "L3",
  "R3",
]);
const DIRECTIONS = Object.freeze({
  up: "DPAD_UP",
  down: "DPAD_DOWN",
  left: "DPAD_LEFT",
  right: "DPAD_RIGHT",
});
const STICKS = new Set(["LEFT_STICK", "RIGHT_STICK"]);
const TRIGGERS = new Set(["LEFT_TRIGGER", "RIGHT_TRIGGER"]);
const DIGITAL = new Set([...BUTTONS, ...Object.values(DIRECTIONS)]);
const validOwner = (owner) =>
  typeof owner === "string" && /^[A-Za-z0-9_:-]{1,64}$/.test(owner);
const clamp = (value, minimum) => Math.max(minimum, Math.min(value, 1));

class GamepadState {
  #digital = new Map();
  #analog = new Map();
  #restored = null;

  setButton(owner, target, down) {
    if (!validOwner(owner) || !DIGITAL.has(target) || typeof down !== "boolean")
      return false;
    const existing = this.#digital.get(owner);
    if (existing === target && !down) this.#digital.delete(owner);
    else if (existing === undefined && down) this.#digital.set(owner, target);
    else return false;
    return true;
  }

  // Sticks use positive Y for up. A zero-valued update releases only this source.
  setAxis(owner, target, x, y = 0) {
    const stick = STICKS.has(target);
    if (
      !validOwner(owner) ||
      (!stick && !TRIGGERS.has(target)) ||
      !Number.isFinite(x) ||
      !Number.isFinite(y) ||
      (!stick && y !== 0)
    )
      return false;

    const existing = this.#analog.get(owner);
    if (existing && existing.target !== target) return false;
    const next = { target, x: clamp(x, stick ? -1 : 0), y: clamp(y, -1) };
    if (next.x === 0 && next.y === 0) {
      if (!existing) return false;
      const wasCurrent = this.#owner(target) === owner;
      this.#analog.delete(owner);
      const restored = wasCurrent && this.#owner(target);
      if (restored)
        this.#restored = { target, owner: restored, released: owner };
      return true;
    }
    // Map insertion order tracks the most recent update across sources.
    this.#analog.delete(owner);
    this.#analog.set(owner, next);
    return true;
  }

  reset() {
    this.#digital.clear();
    this.#analog.clear();
    this.#restored = null;
  }

  #owner(target) {
    let current = null;
    for (const [owner, state] of this.#analog)
      if (state.target === target) current = owner;
    return current;
  }

  ownership() {
    return {
      buttons: Object.fromEntries(this.#digital),
      axes: Object.fromEntries(
        [...STICKS, ...TRIGGERS].map((target) => [target, this.#owner(target)]),
      ),
      restored: this.#restored,
    };
  }

  snapshot() {
    const digital = new Set(this.#digital.values());
    const sources = new Map();
    for (const { target, x, y } of this.#analog.values()) {
      sources.set(target, { x, y });
    }
    return {
      buttons: Object.fromEntries(
        BUTTONS.map((button) => [button, digital.has(button)]),
      ),
      dpad: {
        up: digital.has(DIRECTIONS.up) && !digital.has(DIRECTIONS.down),
        down: digital.has(DIRECTIONS.down) && !digital.has(DIRECTIONS.up),
        left: digital.has(DIRECTIONS.left) && !digital.has(DIRECTIONS.right),
        right: digital.has(DIRECTIONS.right) && !digital.has(DIRECTIONS.left),
      },
      leftStick: sources.get("LEFT_STICK") ?? { x: 0, y: 0 },
      rightStick: sources.get("RIGHT_STICK") ?? { x: 0, y: 0 },
      leftTrigger: sources.get("LEFT_TRIGGER")?.x ?? 0,
      rightTrigger: sources.get("RIGHT_TRIGGER")?.x ?? 0,
    };
  }
}

module.exports = { GamepadState, DIGITAL, STICKS, TRIGGERS };
