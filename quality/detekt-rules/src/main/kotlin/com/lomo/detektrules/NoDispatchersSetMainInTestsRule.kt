package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile

internal class NoDispatchersSetMainInTestsRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Test source must use MainDispatcherExtension instead of calling Dispatchers.setMain / resetMain directly.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isTestFile()) return
        if (file.isMainDispatcherExtensionImpl()) return

        val callee = expression.calleeExpression?.text ?: return
        if (callee != "setMain" && callee != "resetMain") return

        val receiver =
            (expression.parent as? KtDotQualifiedExpression)?.receiverExpression?.text?.substringAfterLast('.')
        if (receiver != null && receiver != "Dispatchers") return

        reportElement(
            expression,
            "Direct Dispatchers.$callee in test source is forbidden. " +
                "Register MainDispatcherExtension (app/src/test/.../MainDispatcherExtension.kt) instead.",
        )
    }

    private fun KtFile.isMainDispatcherExtensionImpl(): Boolean {
        val path = getViewProvider().getVirtualFile().getPath().replace('\\', '/')
        return path.endsWith("/MainDispatcherExtension.kt")
    }
}
