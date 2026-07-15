package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtFile

internal class ForbiddenTestStackImportsRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Test source must use the approved Kotest/MockK/Coroutines stack; JUnit / Mockito / Strikt / AssertK / AssertJ are forbidden.",
) {
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.getViewProvider().getVirtualFile().getPath().replace('\\', '/')
        if (!path.contains("/test/") && !path.contains("/test@android/")) return

        val offending =
            file.importPaths().firstOrNull { import ->
                FORBIDDEN_PREFIXES.any(import::startsWith)
            } ?: return

        reportFile(
            file,
            "Forbidden test stack import: $offending. " +
                "Use Kotest matchers / MockK / kotlinx.coroutines.test / Turbine per quality/testing/ai-kotlin-test-style.md.",
        )
    }

    private companion object {
        val FORBIDDEN_PREFIXES =
            listOf(
                "org.junit.",
                "org.mockito.",
                "strikt.",
                "assertk.",
                "org.assertj.",
                "kotlin.test.",
            )
    }
}
