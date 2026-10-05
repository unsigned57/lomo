package com.lomo.app.feature.preferences

// adversarial-audit: hypothesis: a selected custom font whose file EXISTS but whose bytes
// can no longer be parsed still reports CustomFontStatus.READY while the rendered family silently
// degrades to SansSerif — "selection succeeded but has no effect", the exact state the package
// claims to have eliminated ("缺失/损坏显示明确错误").

import androidx.compose.ui.text.font.FontFamily
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.domain.model.FontPreference
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.lomo.domain.usecase.SingleDispatcherProvider
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files

/*
 * Seam under test: AppPreferencesState.resolveFontPreference maps `resolveFontPath(id) != null`
 * straight to READY. resolveFontPath only proves file existence; parseability is decided later
 * inside CustomFontHost.familyFor, whose failure collapses to FontFamily.SansSerif. So the
 * "unusable" half of CustomFontStatus.MISSING's own KDoc ("missing or unusable") is unreachable:
 * a corrupt-but-present font reports READY and silently renders the system font.
 */
/*
 * Behavior Contract:
 * - Unit under test: AppPreferencesState custom-font status derivation.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: a selected custom font whose file is missing OR unparseable surfaces
 *   CustomFontStatus.MISSING — the explicit problem state — instead of silently degrading to
 *   the system font while claiming READY.
 *
 * Scenarios:
 * - Given a selected font whose file exists but is unparseable, when status resolves, then it is
 *   not READY.
 * - Given a selected font whose file is missing, when status resolves, then it is MISSING.
 * - Given a selected font that exists and parses, when status resolves, then it is READY.
 *
 * Observable outcomes: CustomFontStatus value consumed by the settings surface.
 *
 * TDD proof:
 * - The unparseable arm fails RED while status was derived from file existence alone.
 *
 * Excludes:
 * - Font rendering pixels and the import flow (data-layer specs cover storage).
 */
class CustomFontStatusContractTest : AppFunSpec() {
    init {
        test("selected font file exists but is unparseable: status must not claim READY") {
            runTest {
                val garbage = Files.createTempFile("font-garbage", ".ttf").toFile()
                garbage.writeBytes("not a font at all".toByteArray())
                val store = FakeCustomFontStore()
                store.registerFontPath("broken.ttf", garbage.absolutePath)
                val host =
                    CustomFontHost(
                        customFontStore = store,
                        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
                        // mirrors PlatformFontFamilyLoader: unparseable bytes -> null
                        loader = FontFamilyLoader { null },
                    )
                val appConfigRepository = FakeAppConfigRepository()
                appConfigRepository.setFontPreference(FontPreference.UserImported("broken.ttf"))

                val state =
                    appConfigRepository
                        .observeAppPreferences(store, host)
                        .first()

                // The rendered family already silently degraded to the system font...
                state.customFontFamily shouldBe FontFamily.SansSerif
                // ...yet the status still claims READY. Spec: "缺失/损坏显示明确错误";
                // CustomFontStatus.MISSING KDoc covers "missing or unusable". FAILS today:
                // status is READY because resolveFontPath only proves the file exists.
                state.customFontStatus shouldBe CustomFontStatus.MISSING
            }
        }

        test("selected font file missing: status is MISSING") {
            runTest {
                val store = FakeCustomFontStore()
                val host =
                    CustomFontHost(
                        customFontStore = store,
                        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
                        loader = FontFamilyLoader { null },
                    )
                val appConfigRepository = FakeAppConfigRepository()
                appConfigRepository.setFontPreference(FontPreference.UserImported("gone.ttf"))

                val state =
                    appConfigRepository
                        .observeAppPreferences(store, host)
                        .first()

                state.customFontStatus shouldBe CustomFontStatus.MISSING
                state.customFontFamily shouldBe FontFamily.SansSerif
            }
        }

        test("selected font file exists and parses: status is READY") {
            runTest {
                val healthy = Files.createTempFile("font-healthy", ".ttf").toFile()
                healthy.writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(64))
                val store = FakeCustomFontStore()
                store.registerFontPath("ok.ttf", healthy.absolutePath)
                val host =
                    CustomFontHost(
                        customFontStore = store,
                        dispatcherProvider = SingleDispatcherProvider(Dispatchers.Unconfined),
                        loader = FontFamilyLoader { FontFamily.Monospace },
                    )
                val appConfigRepository = FakeAppConfigRepository()
                appConfigRepository.setFontPreference(FontPreference.UserImported("ok.ttf"))

                val state =
                    appConfigRepository
                        .observeAppPreferences(store, host)
                        .first()

                state.customFontStatus shouldBe CustomFontStatus.READY
                state.customFontFamily shouldNotBe FontFamily.SansSerif
            }
        }
    }
}
