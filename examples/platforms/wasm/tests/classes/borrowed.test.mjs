import { assert, demo } from "../support/index.mjs";

export async function run() {
  const counter = demo.Counter.new(42);
  assert.equal(demo.describeCounter(counter), "Counter(value=42)");
  counter.dispose();
}
