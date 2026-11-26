const SENSORS = ["gameRotation", "rotation", "gyroscope", "accelerometer"];
const finite = (value, min, max) =>
  Number.isFinite(value) && value >= min && value <= max;
const vector = (value, count, limit) =>
  Array.isArray(value) &&
  value.length === count &&
  value.every((entry) => finite(entry, -limit, limit));
const object = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

function normalizeQuaternion(q) {
  if (!Array.isArray(q) || q.length !== 4 || !q.every(Number.isFinite))
    throw new Error("Expected four finite quaternion components");
  const norm = Math.hypot(...q);
  if (!Number.isFinite(norm) || norm < 1e-6)
    throw new Error("Invalid quaternion norm");
  return q.map((value) => value / norm);
}

function parseMapping(value) {
  if (
    !object(value) ||
    ![null, "gameRotation", "rotation"].includes(value.orientation) ||
    !object(value.sensors) ||
    Object.keys(value.sensors).length !== SENSORS.length ||
    !["STICK", "MOUSE", "STEERING"].includes(value.mapping) ||
    !["ORIENTATION", "GYROSCOPE"].includes(value.source) ||
    typeof value.target !== "string" ||
    typeof value.gyroEnabled !== "boolean" ||
    typeof value.stale !== "boolean" ||
    !Number.isInteger(value.invalid) ||
    !finite(value.invalid, 0, 1_000_000) ||
    !vector(value.output, 2, 1) ||
    Math.hypot(...value.output) > 1.000001 ||
    (value.relative !== null && !vector(value.relative, 4, 1.001)) ||
    (value.centerAgeMs !== null &&
      (!Number.isSafeInteger(value.centerAgeMs) || value.centerAgeMs < 0)) ||
    (value.relative === null) !== (value.centerAgeMs === null) ||
    (value.gyro !== null && !vector(value.gyro, 3, 1000)) ||
    (value.accel !== null && !vector(value.accel, 3, 1000))
  )
    throw new Error("Invalid motion diagnostics");
  const { mapping, source, target, gyroEnabled } = value;
  if (
    (mapping === "STICK" && !["LEFT_STICK", "RIGHT_STICK"].includes(target)) ||
    (mapping === "MOUSE" && target !== "POINTER") ||
    (mapping === "STEERING" &&
      (source !== "ORIENTATION" ||
        !/^[A-Z0-9_]{1,16} \/ [A-Z0-9_]{1,16}$/.test(target))) ||
    (source !== "GYROSCOPE" && gyroEnabled)
  )
    throw new Error("Invalid motion target");
  const sensors = {};
  for (const name of SENSORS) {
    const sensor = value.sensors[name];
    if (
      !object(sensor) ||
      typeof sensor.available !== "boolean" ||
      typeof sensor.active !== "boolean" ||
      (sensor.active && !sensor.available) ||
      (sensor.hz !== null && (!sensor.active || !finite(sensor.hz, 0, 1000)))
    )
      throw new Error("Invalid sensor rate");
    sensors[name] = {
      available: sensor.available,
      active: sensor.active,
      hz: sensor.hz,
    };
  }
  if (value.orientation && !sensors[value.orientation].active)
    throw new Error("Inactive orientation source");
  return {
    orientation: value.orientation,
    sensors,
    relative:
      value.relative === null ? null : normalizeQuaternion(value.relative),
    centerAgeMs: value.centerAgeMs,
    gyro: value.gyro,
    accel: value.accel,
    mapping,
    source,
    target,
    gyroEnabled,
    stale: value.stale,
    invalid: value.invalid,
    output: value.output,
  };
}

function parseMotion(value) {
  const primary = parseMapping(value);
  if (value.mappings === undefined) return primary;
  if (
    !Array.isArray(value.mappings) ||
    value.mappings.length < 1 ||
    value.mappings.length > 2
  )
    throw new Error("Invalid mapping count");
  const mappings = value.mappings.map(parseMapping);
  if (
    new Set(mappings.map((m) => m.source)).size !== mappings.length ||
    mappings[0].source !== primary.source ||
    mappings[0].mapping !== primary.mapping ||
    mappings[0].target !== primary.target
  )
    throw new Error("Inconsistent motion mappings");
  return { ...primary, mappings };
}

module.exports = { normalizeQuaternion, parseMotion };
