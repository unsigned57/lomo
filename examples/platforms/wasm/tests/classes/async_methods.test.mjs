import { assert, assertArrayEqual, assertRejectsWithMessage, demo } from "../support/index.mjs";

export async function run() {
  const worker = demo.AsyncWorker.new("test");
  assert.equal(worker.getPrefix(), "test");
  assert.equal(await worker.process("data"), "test: data");
  assert.equal(await worker.tryProcess("data"), "test: data");
  await assertRejectsWithMessage(() => worker.tryProcess(""), "input must not be empty");
  assert.equal(await worker.findItem(42), "test_42");
  assert.equal(await worker.findItem(-1), null);
  assertArrayEqual(await worker.processBatch(["x", "y"]), ["test: x", "test: y"]);
  worker.dispose();
}
