package com.lomo.detektrules

/*
 * Behavior Contract:
 * - Unit under test: NoHardcodedVisibleText Detekt rule.
 * - Owning layer: quality/detekt-rules.
 * - Priority tier: P1.
 * - Capability: reject user-visible Kotlin text literals before they bypass Android string resources.
 *
 * Scenarios:
 * - Given Compose UI passes a literal to Text or contentDescription, when Detekt runs, then the
 *   literal is reported.
 * - Given Android feedback APIs receive literal user copy, when Detekt runs, then the literal is reported.
 * - Given UI copy is resolved from stringResource, getString, or R.string, when Detekt runs, then it is allowed.
 * - Given non-user labels, structural tokens, or user-content templates are present, when Detekt runs, then
 *   they are not treated as localizable copy.
 *
 * Observable outcomes:
 * - Registered rule presence, finding count, and finding message text for source fixtures.
 *
 * TDD proof:
 * - RED: this test fails before the fix because NoHardcodedVisibleText is not registered.
 *
 * Excludes:
 * - Android XML hardcoded text, full Gradle Detekt task wiring, runtime locale selection, and resource key parity.
 */

import dev.detekt.api.Config
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class NoHardcodedVisibleTextRuleTest : FunSpec({
    test("registers hardcoded visible text rule in the architecture rule set") {
        val rules = LomoArchitectureRuleSetProvider().instance().rules

        rules[RuleName("NoHardcodedVisibleText")].shouldNotBeNull()
    }

    test("reports Compose visible text and content descriptions backed by literals") {
        val findings =
            rule().findingsForSource(
                relativePath = "app/src/main/java/com/lomo/app/feature/sample/BadText.kt",
                code =
                    """
                    package com.lomo.app.feature.sample

                    import androidx.compose.material3.Icon
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    @Composable
                    fun BadText() {
                        Text("Hardcoded title")
                        Icon(imageVector = icon(), contentDescription = "Save memo")
                    }
                    """,
            )

        findings.shouldHaveSize(2)
        findings[0].message shouldContain "string resource"
        findings[1].message shouldContain "string resource"
    }

    test("reports Android feedback and clipboard labels backed by literals") {
        val findings =
            rule().findingsForSource(
                relativePath = "app/src/main/java/com/lomo/app/feature/sample/BadFeedback.kt",
                code =
                    """
                    package com.lomo.app.feature.sample

                    import android.content.ClipData
                    import android.content.Context
                    import android.widget.Toast
                    import androidx.compose.material3.SnackbarHostState

                    suspend fun badFeedback(
                        context: Context,
                        snackbarHostState: SnackbarHostState,
                        content: String,
                    ) {
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        snackbarHostState.showSnackbar("Saved")
                        ClipData.newPlainText("Lomo Memo", content)
                    }
                    """,
            )

        findings.shouldHaveSize(3)
        findings.map { it.message }.toString() shouldContain "Copied"
        findings.map { it.message }.toString() shouldContain "Saved"
        findings.map { it.message }.toString() shouldContain "Lomo Memo"
    }

    test("allows resource backed copy and non-copy source literals") {
        val findings =
            rule().findingsForSource(
                relativePath = "ui-components/src/main/java/com/lomo/ui/component/sample/GoodText.kt",
                code =
                    """
                    package com.lomo.ui.component.sample

                    import android.content.Context
                    import android.widget.Toast
                    import androidx.compose.animation.Crossfade
                    import androidx.compose.material3.Icon
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable
                    import androidx.compose.ui.res.stringResource
                    import com.lomo.ui.R

                    @Composable
                    fun GoodText(tag: String) {
                        Text(stringResource(R.string.action_copy))
                        Icon(imageVector = icon(), contentDescription = stringResource(R.string.action_copy))
                        Text("#${'$'}tag")
                        Text("\\u00b7\\u00b7\\u00b7")
                        Crossfade(targetState = tag, label = "TagTransition") {
                            Text(text = it)
                        }
                    }

                    fun goodToast(context: Context) {
                        Toast.makeText(context, context.getString(R.string.action_copy), Toast.LENGTH_SHORT).show()
                    }
                    """,
            )

        findings shouldBe emptyList()
    }
})

private fun rule(
    config: Config = Config.empty,
): Rule =
    checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName("NoHardcodedVisibleText")]) {
        "Expected NoHardcodedVisibleText to be registered."
    }.invoke(config)

private fun Rule.findingsForSource(
    relativePath: String,
    code: String,
): List<dev.detekt.api.Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
