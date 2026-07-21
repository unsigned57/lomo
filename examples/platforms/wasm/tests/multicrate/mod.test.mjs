import { assert, assertThrowsWithMessage, demo } from "../support/index.mjs";

function kindName(kind) {
  switch (kind) {
    case demo.ForeignKind.Standard:
      return "standard";
    case demo.ForeignKind.Express:
      return "express";
    case demo.ForeignKind.Archive:
      return "archive";
    default:
      throw new Error(`unknown ForeignKind: ${kind}`);
  }
}

export async function run() {
  const user = { name: "Ada", age: 37 };
  const session = { id: 7, user, kind: demo.ForeignKind.Express };
  const labeler = {
    label(labelUser, kind) {
      return `label:${labelUser.name}:${kindName(kind)}`;
    },
  };

  assert.equal(demo.multiEchoKind(demo.ForeignKind.Archive), demo.ForeignKind.Archive);
  assert.equal(demo.multiKindLabel(demo.ForeignKind.Express), "express");
  assert.deepEqual(demo.multiShiftPoint({ x: 1, y: 2 }, 3, 4), { x: 4, y: 6 });
  assert.equal(demo.multiPointSum({ x: 2, y: 5 }), 7);
  assert.equal(demo.multiUserSummary(user), "Ada#37");
  assert.equal(demo.multiEchoCode("mc-7"), "mc-7");
  assert.equal(demo.multiCodeValue("mc-7"), "mc-7");
  assert.equal(demo.multiStateSummary({ tag: "Ready" }), "ready");
  assert.equal(demo.multiStateSummary({ tag: "Busy", reason: "sync" }), "busy:sync");
  assert.deepEqual(demo.multiMakeSession(7, user, demo.ForeignKind.Express), session);
  assert.equal(demo.multiSessionSummary(session), "7#Ada#37#express");
  assert.equal(
    demo.multiTotalAge([
      session,
      { id: 8, user: { name: "Grace", age: 41 }, kind: demo.ForeignKind.Archive },
    ]),
    78,
  );
  assert.equal(demo.multiOptionalUserName(session), "Ada");
  assert.equal(demo.multiOptionalUserName(null), null);
  assert.equal(demo.multiEventSummary({ tag: "Started", session }), "started:7#Ada#37#express");
  assert.equal(demo.multiEventSummary({ tag: "Stopped" }), "stopped");
  assert.deepEqual(demo.multiTrySession(9, user, demo.ForeignKind.Archive), {
    id: 9,
    user,
    kind: demo.ForeignKind.Archive,
  });
  assertThrowsWithMessage(
    () => demo.multiTrySession(0, user, demo.ForeignKind.Standard),
    "session id must be positive",
  );
  assert.equal(demo.multiBorrowedSummary(user, session, demo.ForeignKind.Archive), "Ada#37#7#express#archive");
  assert.equal(demo.multiFormatWithLabeler(labeler, user, demo.ForeignKind.Express), "label:Ada:express");

  assert.equal(demo.modelEchoKind(demo.ForeignKind.Standard), demo.ForeignKind.Standard);
  assert.equal(demo.modelKindLabel(demo.ForeignKind.Archive), "archive");
  assert.deepEqual(demo.modelShiftPoint({ x: 2, y: 3 }, 5, 7), { x: 7, y: 10 });
  assert.equal(demo.modelPointSum({ x: 4, y: 6 }), 10);
  assert.equal(demo.modelUserSummary(user), "Ada#37");
  assert.equal(demo.modelEchoCode("dep"), "dep");
  assert.equal(demo.modelCodeValue("dep"), "dep");
  assert.equal(demo.modelStateSummary({ tag: "Busy", reason: "dep" }), "busy:dep");
  assert.equal(demo.modelFormatWithLabeler(labeler, user, demo.ForeignKind.Archive), "label:Ada:archive");

  assert.deepEqual(demo.sessionMake(7, user, demo.ForeignKind.Express), session);
  assert.equal(demo.sessionSummary(session), "7#Ada#37#express");
  assert.equal(demo.sessionTotalAge([session]), 37);
  assert.equal(demo.sessionOptionalUserName(session), "Ada");
  assert.equal(demo.sessionOptionalUserName(null), null);
  assert.equal(demo.sessionEventSummary({ tag: "Started", session }), "started:7#Ada#37#express");
  assert.deepEqual(demo.sessionTryMake(7, user, demo.ForeignKind.Standard), {
    id: 7,
    user,
    kind: demo.ForeignKind.Standard,
  });
  assert.equal(demo.sessionApplyLabeler(labeler, user, demo.ForeignKind.Standard), "label:Ada:standard");

  const counter = demo.ForeignCounter.new(10);
  try {
    assert.equal(counter.add(5), 15);
    assert.equal(counter.get(), 15);
    assert.equal(counter.summarizeUser(user, demo.ForeignKind.Standard), "Ada#37#standard#15");
  } finally {
    counter.dispose();
  }

  const emptyBook = demo.SessionBook.new();
  try {
    assert.equal(emptyBook.count(), 0);
    assert.equal(emptyBook.summarizeFirst(demo.ForeignKind.Archive), "empty#archive");
  } finally {
    emptyBook.dispose();
  }

  const book = demo.SessionBook.withSession(session);
  try {
    assert.equal(book.count(), 1);
    assert.equal(book.addSession({ id: 8, user: { name: "Grace", age: 41 }, kind: demo.ForeignKind.Archive }), 2);
    assert.equal(book.summarizeFirst(demo.ForeignKind.Standard), "7#Ada#37#express");
    assert.equal(book.summarizeBorrowed(user, session, demo.ForeignKind.Archive), "Ada#37#7#express#archive");
    assert.deepEqual(book.metricsForPoints([{ x: 1, y: 2 }, { x: 3, y: 4 }]), { score: 10, count: 2 });
  } finally {
    book.dispose();
  }
}
