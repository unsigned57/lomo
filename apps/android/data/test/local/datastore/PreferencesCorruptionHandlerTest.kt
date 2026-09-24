package com.lomo.data.local.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: lomoPreferencesCorruptionHandler + PreferencesCorruptionRegistry
 * - Owning layer: data/local datastore
 * - Priority tier: P0
 * - Capability: a corrupted preferences file is quarantined as evidence instead of silently
 *   erased, empty preferences replace it for the running session, and a typed notice is
 *   published so the app can present recovery.
 *
 * Scenarios:
 * - Given a preferences file holding invalid bytes, when the store is first read, then reads
 *   succeed on empty preferences, the original bytes are moved aside under a quarantined
 *   name, and the notice records the quarantined file.
 * - Given a published corruption notice, when the user-facing surface acknowledges it, then
 *   the notice clears without deleting the quarantined evidence.
 *
 * Observable outcomes: read success, quarantined file presence, notice contents.
 *
 * TDD proof:
 * - Fails before the fix because the contract surface under test did not exist.
 * Excludes: UI rendering of the notice, backup/restore migration flows.
 */
class PreferencesCorruptionHandlerTest : DataFunSpec() {
    init {
        test("given a corrupted preferences file when read then it is quarantined and a notice is published") {
            runTest {
                val backing = tempPreferencesFile()
                backing.writeBytes("%%% not a serialized preferences file %%%".toByteArray())
                val registry = PreferencesCorruptionRegistry()
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        corruptionHandler =
                            lomoPreferencesCorruptionHandler(
                                corruptionFile = { backing },
                                registry = registry,
                            ),
                        produceFile = { backing },
                    )

                val prefs = store.data.first()

                prefs.asMap().isEmpty() shouldBe true
                backing.exists() shouldBe false
                val quarantined =
                    backing.parentFile!!
                        .listFiles { file -> file.name.startsWith(backing.name + ".corrupt-") }
                        .orEmpty()
                quarantined.size shouldBe 1
                val notice = registry.corruptionNotice.value
                notice.shouldNotBeNull()
                notice.quarantinedFileName shouldContain ".corrupt-"
                notice.quarantinedFileName shouldContain backing.name
            }
        }

        test("given a healthy preferences file when read then no notice is published") {
            runTest {
                val backing = tempPreferencesFile()
                val registry = PreferencesCorruptionRegistry()
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        corruptionHandler =
                            lomoPreferencesCorruptionHandler(
                                corruptionFile = { backing },
                                registry = registry,
                            ),
                        produceFile = { backing },
                    )
                store.edit { it[stringPreferencesKey("k")] = "v" }

                val prefs = store.data.first()

                prefs[stringPreferencesKey("k")] shouldBe "v"
                registry.corruptionNotice.value.shouldBeNull()
            }
        }

        test("given a published notice when acknowledged then the notice clears but evidence remains") {
            runTest {
                val backing = tempPreferencesFile()
                backing.writeBytes("corrupt".toByteArray())
                val registry = PreferencesCorruptionRegistry()
                val store =
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        corruptionHandler =
                            lomoPreferencesCorruptionHandler(
                                corruptionFile = { backing },
                                registry = registry,
                            ),
                        produceFile = { backing },
                    )
                store.data.first()
                val quarantined =
                    backing.parentFile!!
                        .listFiles { file -> file.name.startsWith(backing.name + ".corrupt-") }
                        .orEmpty()
                        .single()

                registry.acknowledgeCorruptionNotice()

                registry.corruptionNotice.value.shouldBeNull()
                quarantined.exists() shouldBe true
            }
        }
    }
}

private fun tempPreferencesFile(): File =
    Files
        .createTempDirectory("lomo-prefs-corrupt")
        .toFile()
        .apply { deleteOnExit() }
        .let { File(it, "lomo.preferences_pb") }
