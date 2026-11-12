package com.lomo.app.architecture

/*
 * Behavior Contract:
 * Capability: the app packaging manifest declares executable reminder/recording capabilities;
 * owning layer: Android composition root; priority: P0.
 * Scenarios:
 * - Given the app manifest consumed by the toolchain, when parsed, then notification, exact
 *   alarm, boot and microphone permissions and background component registrations are present.
 * Observable outcomes: parsed Android permission/component names and microphone service type.
 * TDD proof: RED/GREEN logs under target/lomo/reports/manual/reminder-alarm/manifest-*.log;
 *   pre-fix installed APK lacks these declarations in package-before.txt.
 * Excludes: runtime grants, alarm delivery and manufacturer battery policies.
 */

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidPlatformManifestTest : AppFunSpec() {
    init {
        test("given the packaging manifest when parsed then reminder and recording capabilities are registered") {
            val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { it.resolve("apps/android/app/src/AndroidManifest.xml").isFile }
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(root.resolve("apps/android/app/src/AndroidManifest.xml"))
            fun elements(tag: String): List<Element> {
                val nodes = document.getElementsByTagName(tag)
                return (0 until nodes.length).map { nodes.item(it) as Element }
            }
            fun Element.android(attribute: String) =
                getAttributeNS("http://schemas.android.com/apk/res/android", attribute)

            elements("uses-permission").map { it.android("name") }.shouldContainAll(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.USE_EXACT_ALARM",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.RECORD_AUDIO",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            )
            val receivers = elements("receiver").associateBy { it.android("name") }
            listOf("ReminderAlarmReceiver", "ReminderActionReceiver").forEach { name ->
                receivers.getValue("com.lomo.data.reminder.$name").android("exported") shouldBe "false"
            }
            receivers.getValue("com.lomo.data.reminder.ReminderBootReceiver").android("exported") shouldBe "true"
            receivers.getValue("com.lomo.data.recording.RecordingActionReceiver").android("exported") shouldBe "false"
            val recording = elements("service").single {
                it.android("name") == "com.lomo.data.recording.RecordingForegroundService"
            }
            recording.android("foregroundServiceType") shouldBe "microphone"
            recording.android("exported") shouldBe "false"
        }
    }
}
