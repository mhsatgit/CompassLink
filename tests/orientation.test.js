const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");

async function math() {
  const source = await fs.readFile(
    require("node:path").join(__dirname, "../pc/orientation.js"),
    "utf8",
  );
  return import(
    `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
  );
}
const near = (actual, expected) =>
  assert.ok(Math.abs(actual - expected) < 1e-6, `${actual} != ${expected}`);

test("Android north/east/west headings and ENU conversion", async () => {
  const { orientationAngles, rotate, toView } = await math();
  const s = Math.SQRT1_2;
  near(orientationAngles([0, 0, 0, 1]).heading, 0);
  near(orientationAngles([0, 0, -s, s]).heading, 90);
  near(orientationAngles([0, 0, s, s]).heading, 270);
  const top = toView(rotate([0, 1, 0], [0, 0, 0, 1]));
  near(top[0], 0);
  near(top[1], 0);
  near(top[2], -1);
  assert.equal(orientationAngles([s, 0, 0, s]).headingDefined, false);
  near(orientationAngles([0.2, 0.3, 0.4, 0.5]).heading, 296.565051177078);
  near(orientationAngles([-2, -3, -4, -5]).heading, 296.565051177078);
  assert.equal(orientationAngles([0, 0, 0, 0]).headingDefined, false);
});

test("quaternion sign changes preserve pose and full rotations preserve vectors", async () => {
  const { rotate } = await math();
  const q = [0, Math.SQRT1_2, 0, Math.SQRT1_2];
  rotate([0, 0, 1], q).forEach((n, i) => near(n, [1, 0, 0][i]));
  rotate([1, 2, 3], q).forEach((n, i) =>
    near(
      n,
      rotate(
        [1, 2, 3],
        q.map((v) => -v),
      )[i],
    ),
  );
  rotate([1, 2, 3], [0, 0, 0, -1]).forEach((n, i) => near(n, [1, 2, 3][i]));
});
