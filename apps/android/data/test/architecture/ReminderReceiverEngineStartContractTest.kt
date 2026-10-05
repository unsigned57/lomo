// adversarial-audit: ReminderAlarmReceiver and ReminderActionReceiver consume
// engine-backed repositories (MemoQueryRepository.getMemoById, MarkdownWorkspaceRepository,
// ReminderCoordinator.markDone/recordFired/snooze) from broadcasts that can spawn a cold process.
// The process-duty repair deleted ManagedEngineSession's init-time engine open and made engine acquisition explicit
// via EngineReadinessRepository.requestEngineStart; these receivers were never converted, so in a
// broadcast-spawned process every engine call hits withActiveWorkspaceAdapter's
// "Workspace engine is not Ready" check and the reminder notification is silently dropped
// (ReminderAsyncRunner converts the throw to ReminderReceiverWorkResult.Failed with no retry and
// no log).

package com.lomo.data.architecture

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: reminder broadcast receivers' explicit engine-start wiring.
 * - Owning layer: data (architecture boundary probe over data source).
 * - Priority tier: P0.
 * - Capability: a broadcast-spawned cold process must request engine start explicitly before any
 *   engine-backed repository call; without it, reminder notifications die silently.
 *
 * Scenarios:
 * - Given a broadcast-spawned process, when the alarm receiver runs, then it calls
 *   requestEngineStart before memoQueryRepository/renderMarkdown reads.
 * - Given a broadcast-spawned process, when the reminder action receiver runs, then it calls
 *   requestEngineStart before snooze/done mutations.
 *
 * Observable outcomes: receiver sources contain the explicit requestEngineStart call.
 *
 * TDD proof:
 * - RED while receivers consumed engine-backed repositories without requesting start; GREEN once
 *   both receivers request the engine first.
 *
 * Excludes:
 * - Engine internals and notification rendering; engine readiness itself is covered by
 *   domain/data session specs.
 */
class ReminderReceiverEngineStartContractTest : DataFunSpec() {
    init {
        test("given a broadcast-spawned process when the alarm receiver runs then it explicitly requests the engine before engine-backed reads") {
            val source =
                resolveModuleRoot("data")
                    .resolve("src/reminder/ReminderAlarmReceiver.kt")
                    .readText()

            withClue(
                "ReminderAlarmReceiver performs memoQueryRepository.getMemoById and " +
                    "markdownWorkspaceRepository.renderMarkdown; without an explicit " +
                    "requestEngineStart a broadcast-spawned process keeps readiness=Opening " +
                    "forever and the alarm-fired reminder dies as IllegalStateException",
            ) {
                source.contains("requestEngineStart") shouldBe true
            }
        }

        test("given a broadcast-spawned process when the reminder action receiver runs then it explicitly requests the engine before snooze or done mutations") {
            val source =
                resolveModuleRoot("data")
                    .resolve("src/reminder/ReminderActionReceiver.kt")
                    .readText()

            withClue(
                "ReminderActionReceiver snooze/markDone reach sessionSnoozeReminder and " +
                    "memoQueryRepository.getMemoById/commitDocumentMutation — all Ready-gated " +
                    "engine calls that throw when no requester opened the engine in this process",
            ) {
                source.contains("requestEngineStart") shouldBe true
            }
        }
    }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                currentDir.parentFile?.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots
                .filterNotNull()
                .firstOrNull { dir ->
                    dir.name == moduleName && dir.resolve("module.yaml").exists()
                },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
