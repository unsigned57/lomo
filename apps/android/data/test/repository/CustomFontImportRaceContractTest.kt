package com.lomo.data.repository

// adversarial-audit: hypothesis: importFont has no mutual exclusion — uniqueTarget()
// performs an exists() check and renameTo() with no lock between them, so two concurrent imports
// of the same file name can both observe "name free" and both publish; on POSIX rename(2)
// atomically REPLACES the destination, so the second import silently overwrites the first one's
// bytes while both report Imported — the "conflict suffix keeps both identities" invariant only
// holds for sequential imports.

/*
 * Behavior Contract:
 * - Unit under test: CustomFontStoreImpl.importFont name claiming under concurrency.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: two concurrent imports of the same file name can never both observe "name free";
 *   the loser gets a conflict suffix, so both identities survive with their own bytes.
 *
 * Scenarios:
 * - Given two concurrent same-name imports, when both complete, then both report Imported and
 *   neither silently overwrites the other's bytes.
 *
 * Observable outcomes: imported file set contents and per-import reported names.
 *
 * TDD proof:
 * - RED when exists()+renameTo() ran unlocked: POSIX rename atomically replaced the first
 *   import's bytes while both reported success.
 *
 * Excludes:
 * - Font parsing/validation and the settings surface that lists imported fonts.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.CustomFontImportResult
import com.lomo.domain.model.CustomFontSource
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class CustomFontImportRaceContractTest : DataFunSpec() {
    init {
        test("concurrent same-name imports must not silently overwrite each other") {
            runTest {
                val rounds = 15
                var collisions = 0
                repeat(rounds) { round ->
                    val dir = createTempDirectory("import-race-$round").toFile().apply { deleteOnExit() }
                    val fontDir = File(dir, "custom_fonts").apply { mkdirs() }
                    val barrier = CyclicBarrier(2)
                    val store =
                        CustomFontStoreImpl(
                            fontDir = fontDir,
                            fontFileValidator =
                                FontFileValidator {
                                    // both imports finish staging at the same instant, then race
                                    // through uniqueTarget() -> renameTo()
                                    barrier.await(5, TimeUnit.SECONDS)
                                    true
                                },
                            dispatcherProvider = SingleDispatcherProvider(Dispatchers.IO),
                            maxFontFileBytes = MAX_FONT_FILE_BYTES,
                        )
                    val bytesA = VALID_MAGIC + byteArrayOf(0x0A) + ByteArray(32)
                    val bytesB = VALID_MAGIC + byteArrayOf(0x0B) + ByteArray(64)

                    val deferredA = async { store.importFont(sourceOf(bytesA), "Foo.ttf") }
                    val deferredB = async { store.importFont(sourceOf(bytesB), "Foo.ttf") }
                    val results = awaitAll(deferredA, deferredB)

                    val ids = results.filterIsInstance<CustomFontImportResult.Imported>().map { it.info.id }
                    if (ids.size == 2 && ids.toSet().size == 1) {
                        // both reported Imported under the SAME id — one overwrote the other
                        collisions += 1
                        val onDisk = File(fontDir, ids.toSet().single()).readBytes()
                        // whichever bytes won, one imported payload is gone
                        onDisk.contentEquals(bytesB) || onDisk.contentEquals(bytesA)
                    }
                }
                // Spec: "净化原名+冲突后缀" — two accepted imports of the same name must yield
                // two stable identities. FAILS whenever a concurrent pair collapses onto one file.
                collisions shouldBe 0
            }
        }
    }
}

private val VALID_MAGIC = byteArrayOf(0x00, 0x01, 0x00, 0x00)

private fun sourceOf(bytes: ByteArray): CustomFontSource = CustomFontSource { bytes.inputStream() }
