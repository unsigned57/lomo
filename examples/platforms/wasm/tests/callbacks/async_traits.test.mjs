import { assert, assertRejectsWithCode, demo } from "../support/index.mjs";
import { wireErr, wireOk } from "@boltffi/runtime";

export async function run() {
  const asyncFetcher = {
    fetchValue: async (key) => key * 100,
    fetchString: async (input) => input.toUpperCase(),
    fetchJoinedMessage: async (scope, message) => `${scope}::${message.toUpperCase()}`,
  };
  const asyncPointTransformer = {
    transformPoint: async (point) => ({ x: point.x + 50, y: point.y + 60 }),
  };
  const asyncOptionFetcher = {
    find: async (key) => (key > 0 ? BigInt(key) * 1000n : null),
  };
  const asyncOptionalMessageFetcher = {
    findMessage: async (key) => (key > 0 ? `async-message:${key}` : null),
  };
  const asyncResultFormatter = {
    renderMessage: async (scope, message) =>
      scope.length > 0 ? `${scope}::${message.toUpperCase()}` : wireErr(demo.MathError.NegativeInput),
    transformPoint: async (point, status) =>
      status === demo.Status.Inactive
        ? wireErr(demo.MathError.NegativeInput)
        : wireOk({ x: point.x + 500, y: point.y + 600 }),
  };

  assert.equal(await demo.fetchWithAsyncCallback(asyncFetcher, 5), 500);
  assert.equal(await demo.fetchStringWithAsyncCallback(asyncFetcher, "boltffi"), "BOLTFFI");
  assert.equal(
    await demo.fetchJoinedMessageWithAsyncCallback(asyncFetcher, "async", "borrowed strings"),
    "async::BORROWED STRINGS",
  );
  assert.deepEqual(
    await demo.transformPointWithAsyncCallback(asyncPointTransformer, { x: 1, y: 2 }),
    { x: 51, y: 62 },
  );
  assert.equal(await demo.invokeAsyncOptionFetcher(asyncOptionFetcher, 7), 7_000n);
  assert.equal(await demo.invokeAsyncOptionFetcher(asyncOptionFetcher, 0), null);
  assert.equal(await demo.invokeAsyncOptionalMessageFetcher(asyncOptionalMessageFetcher, 9), "async-message:9");
  assert.equal(await demo.invokeAsyncOptionalMessageFetcher(asyncOptionalMessageFetcher, 0), null);
  assert.equal(
    await demo.renderMessageWithAsyncResultCallback(asyncResultFormatter, "async", "result"),
    "async::RESULT",
  );
  assert.deepEqual(
    await demo.transformPointWithAsyncResultCallback(asyncResultFormatter, { x: 3, y: 4 }, demo.Status.Active),
    { x: 503, y: 604 },
  );
  await assertRejectsWithCode(
    () => demo.renderMessageWithAsyncResultCallback(asyncResultFormatter, "", "result"),
    demo.MathErrorException,
    demo.MathError.NegativeInput,
  );
  await assertRejectsWithCode(
    () => demo.transformPointWithAsyncResultCallback(asyncResultFormatter, { x: 3, y: 4 }, demo.Status.Inactive),
    demo.MathErrorException,
    demo.MathError.NegativeInput,
  );

  const asyncFetcherHandle = demo.registerAsyncFetcher(asyncFetcher);
  const asyncOptionFetcherHandle = demo.registerAsyncOptionFetcher(asyncOptionFetcher);

  assert.ok(asyncFetcherHandle > 0);
  assert.ok(asyncOptionFetcherHandle > 0);

  demo.unregisterAsyncFetcher(asyncFetcherHandle);
  demo.unregisterAsyncOptionFetcher(asyncOptionFetcherHandle);
}
