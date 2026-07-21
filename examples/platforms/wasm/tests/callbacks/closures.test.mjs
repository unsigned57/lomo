import { wireErr } from "@boltffi/runtime";
import { assert, assertApprox, assertArrayEqual, assertPoint, assertThrowsWithCode, demo } from "../support/index.mjs";

export async function run() {
  assert.equal(demo.applyClosure((value) => value * 2, 5), 10);
  assert.equal(demo.applyBinaryClosure((left, right) => left + right, 3, 4), 7);

  let observedValue = null;
  demo.applyVoidClosure((value) => {
    observedValue = value;
  }, 42);
  assert.equal(observedValue, 42);

  assert.equal(demo.applyNullaryClosure(() => 99), 99);
  assert.equal(demo.applyStringClosure((value) => value.toUpperCase(), "hello"), "HELLO");
  assert.equal(demo.applyBoolClosure((value) => !value, true), false);
  assertApprox(demo.applyF64Closure((value) => value * value, 3), 9, 1e-12);
  assertArrayEqual(demo.mapVecWithClosure((value) => value * 2, [1, 2, 3]), [2, 4, 6]);
  assertArrayEqual(demo.filterVecWithClosure((value) => value % 2 === 0, [1, 2, 3, 4]), [2, 4]);
  assert.equal(demo.applyOffsetClosure((value, delta) => value + delta, -5, 8), 3);
  assert.equal(
    demo.applyStatusClosure(
      (status) => (status === demo.Status.Active ? demo.Status.Pending : demo.Status.Active),
      demo.Status.Active,
    ),
    demo.Status.Pending,
  );
  assertPoint(demo.applyPointClosure((point) => ({ x: point.x + 1, y: point.y + 1 }), { x: 1, y: 2 }), { x: 2, y: 3 });
  assertPoint(
    demo.applyOptionalPointClosure(
      (point) => (point ? { x: point.x + 2, y: point.y + 3 } : null),
      { x: 1, y: 2 },
    ),
    { x: 3, y: 5 },
  );
  assert.equal(demo.applyOptionalPointClosure((point) => point, null), null);
  assert.equal(demo.applyResultClosure((value) => value * 4, 6), 24);
  assertThrowsWithCode(
    () => demo.applyResultClosure(() => wireErr(demo.MathError.NegativeInput), -1),
    demo.MathErrorException,
    demo.MathError.NegativeInput,
  );

  const closureBoolHandle = demo.registerClosureBoolToBool((value) => !value);
  const closureF64Handle = demo.registerClosureF64ToF64((value) => value + 1);
  const closureI32Handle = demo.registerClosureI32(() => {});
  const closureI32ToBoolHandle = demo.registerClosureI32ToBool((value) => value > 0);
  const closureI32ToI32Handle = demo.registerClosureI32ToI32((value) => value * 3);
  const closureI32ToResultI32ErrMathErrorHandle = demo.registerClosureI32ToResultI32ErrMathError((value) =>
    value >= 0 ? value : wireErr(demo.MathError.NegativeInput),
  );
  const closureI32I32ToI32Handle = demo.registerClosureI32I32ToI32((left, right) => left + right);
  const closureISizeUSizeToISizeHandle = demo.registerClosureISizeUSizeToISize((value, delta) => value + delta);
  const closureOptPointToOptPointHandle = demo.registerClosureOptPointToOptPoint((point) =>
    point ? { x: point.x + 2, y: point.y + 3 } : null,
  );
  const closurePointToPointHandle = demo.registerClosurePointToPoint((point) => ({ x: point.x, y: point.y }));
  const closureStringToStringHandle = demo.registerClosureStringToString((value) => value);
  const closureToI32Handle = demo.registerClosureToI32(() => 7);

  assert.ok(closureBoolHandle > 0);
  assert.ok(closureF64Handle > 0);
  assert.ok(closureI32Handle > 0);
  assert.ok(closureI32ToBoolHandle > 0);
  assert.ok(closureI32ToI32Handle > 0);
  assert.ok(closureI32ToResultI32ErrMathErrorHandle > 0);
  assert.ok(closureI32I32ToI32Handle > 0);
  assert.ok(closureISizeUSizeToISizeHandle > 0);
  assert.ok(closureOptPointToOptPointHandle > 0);
  assert.ok(closurePointToPointHandle > 0);
  assert.ok(closureStringToStringHandle > 0);
  assert.ok(closureToI32Handle > 0);

  demo.unregisterClosureBoolToBool(closureBoolHandle);
  demo.unregisterClosureF64ToF64(closureF64Handle);
  demo.unregisterClosureI32(closureI32Handle);
  demo.unregisterClosureI32ToBool(closureI32ToBoolHandle);
  demo.unregisterClosureI32ToI32(closureI32ToI32Handle);
  demo.unregisterClosureI32ToResultI32ErrMathError(closureI32ToResultI32ErrMathErrorHandle);
  demo.unregisterClosureI32I32ToI32(closureI32I32ToI32Handle);
  demo.unregisterClosureISizeUSizeToISize(closureISizeUSizeToISizeHandle);
  demo.unregisterClosureOptPointToOptPoint(closureOptPointToOptPointHandle);
  demo.unregisterClosurePointToPoint(closurePointToPointHandle);
  demo.unregisterClosureStringToString(closureStringToStringHandle);
  demo.unregisterClosureToI32(closureToI32Handle);
}
