import { wireErr, wireOk } from "@boltffi/runtime";
import { assert, assertArrayEqual, assertPoint, assertThrowsWithCode, assertThrowsWithMessage, demo } from "../support/index.mjs";

export async function run() {
  const doubler = { onValue: (value) => value * 2 };
  const tripler = { onValue: (value) => value * 3 };
  const incrementer = demo.makeIncrementingCallback(5);
  const pointTransformer = { transform: (point) => ({ x: point.x + 10, y: point.y + 20 }) };
  const statusMapper = {
    mapStatus: (status) => (status === demo.Status.Pending ? demo.Status.Active : demo.Status.Inactive),
  };
  const flipper = demo.makeStatusFlipper();
  const messageFormatter = {
    formatMessage: (scope, message) => `${scope}::${message.toUpperCase()}`,
  };
  const optionalMessageCallback = {
    findMessage: (key) => (key > 0 ? `message:${key}` : null),
  };
  const resultMessageCallback = {
    renderMessage: (key) => (key >= 0 ? `message:${key}` : wireErr(demo.MathError.NegativeInput)),
  };
  const stringResultMessageCallback = {
    renderMessage: (key) => (key >= 0 ? `string-message:${key}` : wireErr(`invalid callback key ${key}`)),
  };
  const multiMethodCallback = {
    methodA: (value) => value + 1,
    methodB: (left, right) => left * right,
    methodC: () => 5,
  };
  const optionCallback = {
    findValue: (key) => (key > 0 ? key * 10 : null),
  };
  const resultCallback = {
    compute: (value) => (value >= 0 ? value * 10 : wireErr(demo.MathError.NegativeInput)),
  };
  const falliblePointTransformer = {
    transformPoint: (point, status) =>
      status === demo.Status.Inactive
        ? wireErr(demo.MathError.NegativeInput)
        : wireOk({ x: point.x + 100, y: point.y + 200 }),
  };
  const offsetCallback = {
    offset: (value, delta) => value + delta,
  };
  const vecProcessor = {
    process: (values) => values.map((value) => value * value),
  };

  assert.equal(demo.invokeValueCallback(doubler, 4), 8);
  assert.equal(demo.invokeValueCallbackTwice(doubler, 3, 4), 14);
  assert.equal(demo.invokeBoxedValueCallback(doubler, 5), 10);
  assert.equal(incrementer.onValue(4), 9);
  assert.equal(demo.invokeValueCallback(incrementer, 4), 9);
  assert.equal(demo.invokeOptionalValueCallback(doubler, 4), 8);
  assert.equal(demo.invokeOptionalValueCallback(null, 4), 4);
  assert.equal(
    demo.formatMessageWithCallback(messageFormatter, "sync", "borrowed strings"),
    "sync::BORROWED STRINGS",
  );
  assert.equal(
    demo.formatMessageWithBoxedCallback(messageFormatter, "boxed", "borrowed strings"),
    "boxed::BORROWED STRINGS",
  );
  assert.equal(
    demo.formatMessageWithOptionalCallback(messageFormatter, "optional", "borrowed strings"),
    "optional::BORROWED STRINGS",
  );
  assert.equal(
    demo.formatMessageWithOptionalCallback(null, "fallback", "message"),
    "fallback::message",
  );
  const prefixer = demo.makeMessagePrefixer("prefix");
  assert.equal(prefixer.formatMessage("scope", "message"), "prefix::scope::message");
  assert.equal(demo.formatMessageWithCallback(prefixer, "sync", "formatter"), "prefix::sync::formatter");
  assert.equal(demo.invokeOptionalMessageCallback(optionalMessageCallback, 7), "message:7");
  assert.equal(demo.invokeOptionalMessageCallback(optionalMessageCallback, 0), null);
  assert.equal(demo.invokeResultMessageCallback(resultMessageCallback, 8), "message:8");
  assertThrowsWithCode(
    () => demo.invokeResultMessageCallback(resultMessageCallback, -1),
    demo.MathErrorException,
    demo.MathError.NegativeInput,
  );
  globalThis.demoCase("case:callbacks.sync_traits.string_result_message_callback.should_return_encoded_success");
  assert.equal(
    demo.invokeStringResultMessageCallback(stringResultMessageCallback, 11),
    "string-message:11",
  );
  globalThis.demoCase("case:callbacks.sync_traits.string_result_message_callback.should_report_string_error");
  assertThrowsWithMessage(
    () => demo.invokeStringResultMessageCallback(stringResultMessageCallback, -1),
    "invalid callback key -1",
  );
  assertPoint(demo.transformPoint(pointTransformer, { x: 1, y: 2 }), { x: 11, y: 22 });
  assertPoint(demo.transformPointBoxed(pointTransformer, { x: 3, y: 4 }), { x: 13, y: 24 });
  assert.equal(demo.mapStatus(statusMapper, demo.Status.Pending), demo.Status.Active);
  assert.equal(flipper.mapStatus(demo.Status.Active), demo.Status.Inactive);
  assert.equal(demo.mapStatus(flipper, demo.Status.Inactive), demo.Status.Pending);
  assertArrayEqual(demo.processVec(vecProcessor, [1, 2, 3]), [1, 4, 9]);
  assert.equal(demo.invokeMultiMethod(multiMethodCallback, 3, 4), 21);
  assert.equal(demo.invokeMultiMethodBoxed(multiMethodCallback, 3, 4), 21);
  assert.equal(demo.invokeTwoCallbacks(doubler, tripler, 5), 25);
  assert.equal(demo.invokeOptionCallback(optionCallback, 7), 70);
  assert.equal(demo.invokeOptionCallback(optionCallback, 0), null);
  assert.equal(demo.invokeResultCallback(resultCallback, 7), 70);
  assertThrowsWithCode(
    () => demo.invokeResultCallback(resultCallback, -1),
    demo.MathErrorException,
    demo.MathError.NegativeInput,
  );
  assert.equal(demo.invokeOffsetCallback(offsetCallback, -5, 8), 3);
  assert.equal(demo.invokeBoxedOffsetCallback(offsetCallback, 10, 4), 14);
  assertPoint(
    demo.invokeFalliblePointTransformer(falliblePointTransformer, { x: 2, y: 3 }, demo.Status.Active),
    { x: 102, y: 203 },
  );
  assertThrowsWithCode(
    () => demo.invokeFalliblePointTransformer(falliblePointTransformer, { x: 2, y: 3 }, demo.Status.Inactive),
    demo.MathErrorException,
    demo.MathError.NegativeInput,
  );

  const valueCallbackHandle = demo.registerValueCallback(doubler);
  const pointTransformerHandle = demo.registerPointTransformer(pointTransformer);
  const statusMapperHandle = demo.registerStatusMapper(statusMapper);
  const vecProcessorHandle = demo.registerVecProcessor(vecProcessor);
  const multiMethodCallbackHandle = demo.registerMultiMethodCallback(multiMethodCallback);
  const optionCallbackHandle = demo.registerOptionCallback(optionCallback);
  const falliblePointTransformerHandle = demo.registerFalliblePointTransformer(falliblePointTransformer);
  const offsetCallbackHandle = demo.registerOffsetCallback(offsetCallback);

  assert.ok(valueCallbackHandle > 0);
  assert.ok(pointTransformerHandle > 0);
  assert.ok(statusMapperHandle > 0);
  assert.ok(vecProcessorHandle > 0);
  assert.ok(multiMethodCallbackHandle > 0);
  assert.ok(optionCallbackHandle > 0);
  assert.ok(falliblePointTransformerHandle > 0);
  assert.ok(offsetCallbackHandle > 0);

  demo.unregisterValueCallback(valueCallbackHandle);
  demo.unregisterPointTransformer(pointTransformerHandle);
  demo.unregisterStatusMapper(statusMapperHandle);
  demo.unregisterVecProcessor(vecProcessorHandle);
  demo.unregisterMultiMethodCallback(multiMethodCallbackHandle);
  demo.unregisterOptionCallback(optionCallbackHandle);
  demo.unregisterFalliblePointTransformer(falliblePointTransformerHandle);
  demo.unregisterOffsetCallback(offsetCallbackHandle);
}
