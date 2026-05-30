package com.lomo.data.reminder

/*
 * Behavior Contract:
 * - Unit under test: com.lomo.data.reminder.ReminderRequestCodePolicy
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: derive stable reminder alarm, notification, and notification-action identities from
 *   explicit memo/token/action inputs without relying on Java String.hashCode() persistence.
 *
 * Scenarios:
 * - Given the same memo id and reminder token, when alarm and notification ids are requested more than
 *   once, then the returned ids are stable.
 * - Given the same memo id, reminder token, and action, when an action request code is requested more
 *   than once, then the returned request code is stable.
 * - Given the same memo id and reminder token with different notification actions, when action request
 *   codes are requested, then the returned request codes differ.
 * - Given representative reminder inputs, when request-code ids are derived, then the SHA-256 namespace,
 *   UTF-8, first-four-byte big-endian, positive-Int contract is pinned by golden values.
 * - Given legacy Java String.hashCode() request-code formulas, when policy ids are requested, then alarm
 *   notification, and action request codes do not reuse the legacy persisted identities.
 * - Given alarm scheduling, notification display, and notification actions need ids, when callers request
 *   them, then each id comes from the same explicit reminder request-code policy.
 *
 * Observable outcomes:
 * - Returned Int alarm request codes, notification ids, and action request codes.
 *
 * TDD proof:
 * - Target command: ./gradlew --no-daemon --no-configuration-cache --console=plain
 *   :data:testDebugUnitTest --tests 'com.lomo.data.reminder.ReminderRequestCodePolicyTest'
 * - Observed RED: test compilation failed with unresolved reference errors for ReminderRequestCodePolicy.
 * - Why RED proves the behavior was missing: reminder request-code identity was still embedded in callers
 *   instead of being exposed as a stable, explicit, testable data-layer policy.
 *
 * Excludes:
 * - Android PendingIntent delivery, NotificationManager rendering, SHA-256 collision exhaustiveness, and
 *   receiver business behavior after an action is delivered.
 */

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ReminderRequestCodePolicyTest : FunSpec({
    test("given the same memo token and action when ids are requested then policy results are stable") {
        val memoId = "memo-2026-05-22"
        val tokenRaw = "@remind(2026-05-22T09:30 repeat=3 every=15m)"
        val action = ReminderIntents.ACTION_SNOOZE

        assertSoftly {
            ReminderRequestCodePolicy.alarmRequestCode(memoId, tokenRaw) shouldBe
                ReminderRequestCodePolicy.alarmRequestCode(memoId, tokenRaw)
            ReminderRequestCodePolicy.notificationId(memoId, tokenRaw) shouldBe
                ReminderRequestCodePolicy.notificationId(memoId, tokenRaw)
            ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, action) shouldBe
                ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, action)
        }
    }

    test("given different notification actions when request codes are requested then action identities differ") {
        val memoId = "memo-2026-05-22"
        val tokenRaw = "@remind(2026-05-22T09:30 repeat=3 every=15m)"

        ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, ReminderIntents.ACTION_SNOOZE) shouldNotBe
            ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, ReminderIntents.ACTION_DONE)
    }

    test("given representative reminder inputs when request codes are derived then golden ids stay stable") {
        val memoId = "memo-2026-05-22"
        val tokenRaw = "@remind(2026-05-22T09:30 repeat=3 every=15m)"

        assertSoftly {
            ReminderRequestCodePolicy.alarmRequestCode(memoId, tokenRaw) shouldBe 1_401_853_463
            ReminderRequestCodePolicy.notificationId(memoId, tokenRaw) shouldBe 2_081_756_623
            ReminderRequestCodePolicy.actionRequestCode(
                memoId,
                tokenRaw,
                ReminderIntents.ACTION_SNOOZE,
            ) shouldBe 1_719_573_182
            ReminderRequestCodePolicy.actionRequestCode(
                memoId,
                tokenRaw,
                ReminderIntents.ACTION_DONE,
            ) shouldBe 35_632_916
        }
    }

    test("given legacy hash formulas when request codes are requested then persisted ids do not reuse hashCode") {
        val memoId = "memo-2026-05-22"
        val tokenRaw = "@remind(2026-05-22T09:30 repeat=3 every=15m)"
        val action = ReminderIntents.ACTION_DONE
        val legacyAlarmRequestCode = "$memoId|$tokenRaw".hashCode()
        val legacyActionRequestCode = (legacyAlarmRequestCode.toString() + action).hashCode()

        assertSoftly {
            ReminderRequestCodePolicy.alarmRequestCode(memoId, tokenRaw) shouldNotBe legacyAlarmRequestCode
            ReminderRequestCodePolicy.notificationId(memoId, tokenRaw) shouldNotBe legacyAlarmRequestCode
            ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, action) shouldNotBe legacyActionRequestCode
        }
    }
})
