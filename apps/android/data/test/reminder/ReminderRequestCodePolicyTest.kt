package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: com.lomo.data.reminder.ReminderRequestCodePolicy
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: derive stable reminder alarm, notification, and notification-action identities from
 *   explicit occurrence/memo/token/action inputs without relying on Java String.hashCode()
 *   persistence. Alarm identity is keyed on the durable Rust occurrence id; the 32-bit request
 *   code is never the sole PendingIntent discriminator (the intent data URI carries the same id).
 *
 * Scenarios:
 * - Given the same occurrence id, when the alarm request code is requested more than once, then
 *   the returned code is stable.
 * - Given the same memo id and reminder token, when notification and action ids are requested more
 *   than once, then the returned ids are stable.
 * - Given the same memo id, reminder token, and action, when an action request code is requested
 *   more than once, then the returned request code is stable.
 * - Given the same memo id and reminder token with different notification actions, when action
 *   request codes are requested, then the returned request codes differ.
 * - Given two occurrences of the same reminder, when alarm request codes and notification tags are
 *   derived, then they differ — occurrence identity is explicit, not memo+reminder aliased.
 * - Given representative inputs, when ids are derived, then the SHA-256 namespace, UTF-8,
 *   first-four-byte big-endian, positive-Int contract is pinned by golden values.
 * - Given legacy Java String.hashCode() request-code formulas, when policy ids are requested, then
 *   alarm, notification, and action request codes do not reuse the legacy persisted identities.
 *
 * Observable outcomes:
 * - Returned Int alarm request codes, notification ids/tags, and action request codes.
 *
 * TDD proof:
 * - Target command: ./kotlin test
 *   :data:testDebugUnitTest --tests 'com.lomo.data.reminder.ReminderRequestCodePolicyTest'
 * - Observed RED: test compilation failed with unresolved reference errors for ReminderRequestCodePolicy.
 * - Why RED proves the behavior was missing: reminder request-code identity was still embedded in
 *   callers instead of being exposed as a stable, explicit, testable data-layer policy.
 *
 * Excludes:
 * - Android PendingIntent delivery, NotificationManager rendering, SHA-256 collision
 *   exhaustiveness, and receiver business behavior after an action is delivered.
 * Test Change Justification:
 * - Reason category: domain contract change (occurrence-keyed alarm identity).
 * - Old behavior/assertion being replaced: request codes derived from memo id/reminder token and
 *   legacy String.hashCode formulas.
 * - Why old assertion is no longer correct: the durable occurrence id is the alarm identity; the
 *   32-bit code is never the sole discriminator (the intent data URI carries the id).
 * - Coverage preserved by: stability/determinism scenarios rewritten around occurrence ids.
 * - Why this is not fitting the test to the implementation: the golden namespace/encoding
 *   contract is still pinned.
 */

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ReminderRequestCodePolicyTest : FunSpec({
    val occurrenceId = "gen-a␟rem-1␟1700000000000"
    val otherOccurrenceId = "gen-a␟rem-1␟1700000001000"
    val memoId = "memo-2026-05-22"
    val tokenRaw = "@remind(2026-05-22T09:30 repeat=3 every=15m)"

    test("given the same occurrence and reminder inputs when ids are requested then policy results are stable") {
        val action = ReminderIntents.ACTION_SNOOZE

        assertSoftly {
            ReminderRequestCodePolicy.alarmRequestCode(occurrenceId) shouldBe
                ReminderRequestCodePolicy.alarmRequestCode(occurrenceId)
            ReminderRequestCodePolicy.occurrenceTag(occurrenceId) shouldBe
                ReminderRequestCodePolicy.occurrenceTag(occurrenceId)
            ReminderRequestCodePolicy.notificationId(memoId, tokenRaw) shouldBe
                ReminderRequestCodePolicy.notificationId(memoId, tokenRaw)
            ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, action) shouldBe
                ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, action)
        }
    }

    test("given different notification actions when request codes are requested then action identities differ") {
        ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, ReminderIntents.ACTION_SNOOZE) shouldNotBe
            ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, ReminderIntents.ACTION_DONE)
    }

    test("given two occurrences of one reminder when identities are derived then they differ") {
        assertSoftly {
            ReminderRequestCodePolicy.alarmRequestCode(occurrenceId) shouldNotBe
                ReminderRequestCodePolicy.alarmRequestCode(otherOccurrenceId)
            ReminderRequestCodePolicy.occurrenceTag(occurrenceId) shouldNotBe
                ReminderRequestCodePolicy.occurrenceTag(otherOccurrenceId)
        }
    }

    test("given representative reminder inputs when request codes are derived then golden ids stay stable") {
        assertSoftly {
            ReminderRequestCodePolicy.alarmRequestCode(occurrenceId) shouldBe 972_039_267
            ReminderRequestCodePolicy.notificationId(memoId, tokenRaw) shouldBe 2_081_756_623
            ReminderRequestCodePolicy.actionRequestCode(
                memoId,
                tokenRaw,
                "${ReminderIntents.ACTION_SNOOZE}:$occurrenceId",
            ) shouldBe 835_549_579
            ReminderRequestCodePolicy.actionRequestCode(
                memoId,
                tokenRaw,
                "${ReminderIntents.ACTION_DONE}:$occurrenceId",
            ) shouldBe 2_100_949_961
        }
    }

    test("given legacy hash formulas when request codes are requested then persisted ids do not reuse hashCode") {
        val action = ReminderIntents.ACTION_DONE
        val legacyAlarmRequestCode = "$memoId|$tokenRaw".hashCode()
        val legacyActionRequestCode = (legacyAlarmRequestCode.toString() + action).hashCode()

        assertSoftly {
            ReminderRequestCodePolicy.alarmRequestCode(occurrenceId) shouldNotBe legacyAlarmRequestCode
            ReminderRequestCodePolicy.notificationId(memoId, tokenRaw) shouldNotBe legacyAlarmRequestCode
            ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, action) shouldNotBe legacyActionRequestCode
        }
    }
})
