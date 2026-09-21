/*
 * Behavior Contract:
 * - Unit under test: WorkspaceProcessDuty
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: native engine ownership is a process duty of the default application process.
 *
 * Scenarios:
 * - Given the process name equals the package name, when duty is asked, then the process owns the
 *   native engine.
 * - Given a private-process suffix such as a Glance widget process, when duty is asked, then the
 *   process does not own the native engine.
 * - Given an empty process name, when duty is asked, then ownership is denied so an unknown name
 *   cannot silently mount.
 *
 * Observable outcomes:
 * - ownsNativeEngine boolean for default, widget, and empty process names.
 *
 * TDD proof:
 * - RED before WorkspaceProcessDuty exists because the ownership law is unmodeled and every
 *   process start opened the engine.
 *
 * Excludes:
 * - Android process-name APIs, Koin graph construction, and Glance rendering.
 */
package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class WorkspaceProcessDutyTest : DomainFunSpec() {
    init {
        test("given the default process when duty is asked then it owns the native engine") {
            WorkspaceProcessDuty.ownsNativeEngine(
                packageName = "com.lomo.app",
                processName = "com.lomo.app",
            ) shouldBe true
        }

        test("given a widget process when duty is asked then it does not own the native engine") {
            WorkspaceProcessDuty.ownsNativeEngine(
                packageName = "com.lomo.app",
                processName = "com.lomo.app:widget",
            ) shouldBe false
        }

        test("given an empty process name when duty is asked then ownership is denied") {
            WorkspaceProcessDuty.ownsNativeEngine(
                packageName = "com.lomo.app",
                processName = "",
            ) shouldBe false
        }
    }
}
