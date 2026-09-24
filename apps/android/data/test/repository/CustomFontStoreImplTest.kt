package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.CustomFontImportResult
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.CustomFontNameState
import com.lomo.domain.model.CustomFontRejection
import com.lomo.domain.model.CustomFontSource
import com.lomo.domain.usecase.SingleDispatcherProvider
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID

/*
 * Behavior Contract:
 * - Unit under test: CustomFontStoreImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: imported fonts keep their original display name on a sanitized unique file
 *   identity, stream through a byte budget, pass font validation before atomic publish, and
 *   legacy UUID-named files surface a recognizable migration state instead of a fabricated name.
 *
 * Scenarios:
 * - Given a valid ttf stream named Foo.ttf, when import runs, then the stored file is Foo.ttf
 *   and the display name is Foo with an original name state.
 * - Given Foo.ttf already exists, when another Foo.ttf imports, then a conflict suffix keeps
 *   both identities stable without overwriting.
 * - Given a stream longer than the byte budget, when import runs, then the rejection is typed
 *   and no file is materialized.
 * - Given bytes without an sfnt magic header, when import runs, then the rejection is typed
 *   and no file is materialized.
 * - Given bytes the platform font validator rejects, when import runs, then the rejection is
 *   typed and no file is materialized.
 * - Given a UUID-named file from the pre-migration layout, when fonts are scanned, then the
 *   info carries the legacy-import name state rather than an invented display name.
 * - Given a rejected import, when fonts are scanned afterwards, then no temp or partial file
 *   is left behind.
 *
 * Observable outcomes: CustomFontImportResult branches, on-disk file names, name states,
 * absence of leftover artifacts.
 *
 * TDD proof:
 * - Fails before the fix because the contract surface under test did not exist.
 * Excludes: platform Typeface rasterization, Compose FontFamily construction, UI toasts.
 */
class CustomFontStoreImplTest : DataFunSpec() {
    init {
        test("given valid ttf stream when imported then original name is kept on the file and display") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)

                val result =
                    store.importFont(
                        source = streamOf(VALID_TTF_BYTES),
                        originalFileName = "My Font.ttf",
                    )

                val imported = result.shouldBeInstanceOf<CustomFontImportResult.Imported>()
                imported.info.displayName shouldBe "My Font"
                imported.info.nameState shouldBe CustomFontNameState.ORIGINAL
                imported.info.id shouldBe "My Font.ttf"
                File(dir, "My Font.ttf").readBytes() shouldBe VALID_TTF_BYTES.toList().toByteArray()
            }
        }

        test("given a taken file name when importing the same name then a conflict suffix preserves both") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)
                store.importFont(streamOf(VALID_TTF_BYTES), "Foo.ttf")

                val result = store.importFont(streamOf(VALID_TTF_BYTES), "Foo.ttf")

                val imported = result.shouldBeInstanceOf<CustomFontImportResult.Imported>()
                imported.info.id shouldBe "Foo-2.ttf"
                File(dir, "Foo.ttf").exists() shouldBe true
                File(dir, "Foo-2.ttf").exists() shouldBe true
                store.observeFonts().first().shouldHaveSize(2)
            }
        }

        test("given a hostile source name when imported then the file name is sanitized") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)

                val result =
                    store.importFont(
                        source = streamOf(VALID_TTF_BYTES),
                        originalFileName = "../evil/../%2e%2e/Weird\u0000Name.ttf",
                    )

                val imported = result.shouldBeInstanceOf<CustomFontImportResult.Imported>()
                imported.info.id.contains("..") shouldBe false
                imported.info.id.contains('/') shouldBe false
                imported.info.id.contains('\\') shouldBe false
                imported.info.id.contains('\u0000') shouldBe false
                File(dir, imported.info.id).exists() shouldBe true
            }
        }

        test("given a stream over the byte budget when imported then oversized rejection leaves no file") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir, maxFontFileBytes = VALID_TTF_BYTES.size.toLong())
                val oversized = VALID_TTF_BYTES + byteArrayOf(0)

                val result = store.importFont(streamOf(oversized), "Huge.ttf")

                val rejected = result.shouldBeInstanceOf<CustomFontImportResult.Rejected>()
                rejected.reason shouldBe CustomFontRejection.OVERSIZED
                dir.listFiles().orEmpty().toList() shouldBe emptyList()
            }
        }

        test("given bytes without a font magic header when imported then invalid rejection leaves no file") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)

                val result = store.importFont(streamOf("not a font".toByteArray()), "Bad.ttf")

                val rejected = result.shouldBeInstanceOf<CustomFontImportResult.Rejected>()
                rejected.reason shouldBe CustomFontRejection.INVALID_FONT
                dir.listFiles().orEmpty().toList() shouldBe emptyList()
            }
        }

        test("given platform validator rejection when imported then invalid rejection leaves no file") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir, validator = FontFileValidator { false })

                val result = store.importFont(streamOf(VALID_TTF_BYTES), "Bad.ttf")

                val rejected = result.shouldBeInstanceOf<CustomFontImportResult.Rejected>()
                rejected.reason shouldBe CustomFontRejection.INVALID_FONT
                dir.listFiles().orEmpty().toList() shouldBe emptyList()
            }
        }

        test("given an unreadable source when imported then unreadable rejection leaves no file") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)

                val result = store.importFont(source = { null }, originalFileName = "Gone.ttf")

                val rejected = result.shouldBeInstanceOf<CustomFontImportResult.Rejected>()
                rejected.reason shouldBe CustomFontRejection.UNREADABLE
                dir.listFiles().orEmpty().toList() shouldBe emptyList()
            }
        }

        test("given a uuid-named legacy file when scanned then name state is legacy import") {
            runTest {
                val dir = newFontRoot()
                val legacyId = "${UUID.randomUUID()}.ttf"
                File(dir, legacyId).writeBytes(VALID_TTF_BYTES)
                val store = newStore(dir)

                val fonts = store.observeFonts().first()

                fonts.shouldHaveSize(1)
                fonts[0].id shouldBe legacyId
                fonts[0].nameState shouldBe CustomFontNameState.LEGACY_IMPORT
            }
        }

        test("given a rejected import then no temporary artifact remains") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir, validator = FontFileValidator { false })
                store.importFont(streamOf(VALID_TTF_BYTES), "Bad.ttf")

                dir.listFiles().orEmpty().map(File::getName) shouldBe emptyList()
            }
        }

        test("given an imported font when deleted then the file and the listing entry disappear") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)
                val imported =
                    store
                        .importFont(streamOf(VALID_TTF_BYTES), "Foo.ttf")
                        .shouldBeInstanceOf<CustomFontImportResult.Imported>()

                store.deleteFont(imported.info.id)

                File(dir, imported.info.id).exists() shouldBe false
                store.observeFonts().first() shouldBe emptyList()
            }
        }

        test("given a named file when scanned then resolveFontPath returns its absolute path") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)
                val imported =
                    store
                        .importFont(streamOf(VALID_TTF_BYTES), "Foo.ttf")
                        .shouldBeInstanceOf<CustomFontImportResult.Imported>()

                store.resolveFontPath(imported.info.id) shouldBe File(dir, "Foo.ttf").absolutePath
                store.resolveFontPath("missing.ttf") shouldBe null
                store.resolveFontPath("../escape.ttf") shouldBe null
            }
        }

        test("given an unsafe display stem when imported then a non-blank name is produced") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)

                val result = store.importFont(streamOf(VALID_TTF_BYTES), "...ttf")

                val imported = result.shouldBeInstanceOf<CustomFontImportResult.Imported>()
                imported.info.id.startsWith(".") shouldBe false
                imported.info.id.endsWith(".ttf") shouldBe true
                imported.info.displayName.isNotBlank() shouldBe true
            }
        }

        test("given an unsupported extension when imported then unsupported-type rejection") {
            runTest {
                val dir = newFontRoot()
                val store = newStore(dir)

                val result = store.importFont(streamOf(VALID_TTF_BYTES), "Foo.woff2")

                val rejected = result.shouldBeInstanceOf<CustomFontImportResult.Rejected>()
                rejected.reason shouldBe CustomFontRejection.UNSUPPORTED_TYPE
            }
        }
    }
}

private fun streamOf(bytes: ByteArray): CustomFontSource = CustomFontSource { bytes.inputStream() }

private fun newFontRoot(): File =
    kotlin.io.path
        .createTempDirectory("lomo-font-store")
        .toFile()
        .apply { deleteOnExit() }
        .also { File(it, "custom_fonts").mkdirs() }
        .let { File(it, "custom_fonts") }

private fun newStore(
    fontDir: File,
    validator: FontFileValidator = FontFileValidator { true },
    maxFontFileBytes: Long = MAX_FONT_FILE_BYTES,
): CustomFontStoreImpl =
    CustomFontStoreImpl(
        fontDir = fontDir,
        fontFileValidator = validator,
        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
        maxFontFileBytes = maxFontFileBytes,
    )

// 0x00010000 sfnt version + a few table records — enough for the magic gate.
private val VALID_TTF_BYTES: ByteArray =
    byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
        ByteArray(64)
