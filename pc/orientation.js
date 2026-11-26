// Android device axes: X right, Y top, Z out of screen. World: East, North, Up.
export function multiply(a, b) {
  const [x, y, z, w] = a;
  const [X, Y, Z, W] = b;
  return [
    w * X + x * W + y * Z - z * Y,
    w * Y - x * Z + y * W + z * X,
    w * Z + x * Y - y * X + z * W,
    w * W - x * X - y * Y - z * Z,
  ];
}
export function rotate(vector, q) {
  return multiply(multiply(q, [...vector, 0]), [
    -q[0],
    -q[1],
    -q[2],
    q[3],
  ]).slice(0, 3);
}
// Convert world ENU into a Y-up view: (East, Up, -North). The mesh uses device axes.
export function toView(vector) {
  return [vector[0], vector[2], -vector[1]];
}
export function orientationAngles(q) {
  const size = Math.hypot(...q);
  if (!Number.isFinite(size) || size === 0)
    return { heading: 0, pitch: 0, roll: 0, headingDefined: false };
  const [x, y, z, w] = q.map((component) => component / size);
  const r01 = 2 * (x * y - z * w),
    r11 = 1 - 2 * (x * x + z * z);
  const r20 = 2 * (x * z - y * w),
    r21 = 2 * (y * z + x * w),
    r22 = 1 - 2 * (x * x + y * y);
  const degrees = 180 / Math.PI;
  return {
    // Same azimuth/pitch/roll convention as SensorManager.getOrientation().
    heading: (Math.atan2(r01, r11) * degrees + 360) % 360,
    pitch: Math.asin(-Math.max(-1, Math.min(1, r21))) * degrees,
    roll: Math.atan2(-r20, r22) * degrees,
    headingDefined: Math.hypot(r01, r11) > 1e-5,
  };
}
