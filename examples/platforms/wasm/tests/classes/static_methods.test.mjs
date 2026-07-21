import { assert, assertApprox, assertPoint, demo } from "../support/index.mjs";

export async function run() {
  const mathUtils = demo.MathUtils.new(2);
  assertApprox(mathUtils.round(3.14159), 3.14, 1e-9);
  mathUtils.dispose();

  assert.equal(demo.MathUtils.add(4, 5), 9);
  assertApprox(demo.MathUtils.clamp(12, 0, 10), 10, 1e-12);
  assertApprox(demo.MathUtils.distanceBetween({ x: 0, y: 0 }, { x: 3, y: 4 }), 5, 1e-12);
  assertPoint(demo.MathUtils.midpoint({ x: 1, y: 2 }, { x: 3, y: 4 }), { x: 2, y: 3 });
  assert.equal(demo.MathUtils.parseInt("42"), 42);
  assert.throws(() => demo.MathUtils.parseInt("nope"), /invalid digit found in string/);
  assert.equal(demo.MathUtils.safeSqrt(9), 3);
  assert.equal(demo.MathUtils.safeSqrt(-1), null);
}
