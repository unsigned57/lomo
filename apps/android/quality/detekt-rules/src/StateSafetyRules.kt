package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal class NoStatefulRepositoryOrUseCaseRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Repository and UseCase classes must be stateless pipelines over persistent engines; mutable 'var' member properties are forbidden.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*stateful-var-ok""")

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        if (property.isLocal) return
        if (!property.isVar) return

        val containingClass = property.containingClassOrObject ?: return
        val className = containingClass.name ?: return
        if (!isTargetClass(containingClass)) return

        if (property.hasOptOutComment(optOutMarker)) return

        reportElement(
            property,
            "Stateful 'var' property forbidden in $className: '${property.name}'. " +
                "UseCases and Repositories must be stateless pipelines over persistent engines. " +
                "Use immutability, pass state through arguments, or document with '// behavior-contract: stateful-var-ok: <reason>'.",
        )
    }

    private fun isTargetClass(containingClass: KtClassOrObject): Boolean {
        val className = containingClass.name ?: return false
        if (className.endsWith("UseCase") ||
            className.endsWith("Repository") ||
            className.endsWith("RepositoryImpl")
        ) {
            return true
        }
        return containingClass.superTypeListEntries.any { entry ->
            val text = entry.text
            text.contains("Repository") || text.contains("UseCase")
        }
    }
}

internal class NoAdHocMemoryCacheRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Ad-hoc in-memory Map/Cache member properties in Repository and UseCase classes are forbidden without explicit behavior contracts.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*managed-cache-ok""")
    private val cacheTokens = listOf(
        "mutableMapOf",
        "ConcurrentHashMap",
        "LinkedHashMap",
        "HashMap",
        "LruCache",
        "MutableMap",
    )

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        if (property.isLocal) return

        val containingClass = property.containingClassOrObject ?: return
        val className = containingClass.name ?: return
        if (!isTargetClass(containingClass)) return

        val typeText = property.typeReference?.text.orEmpty()
        val initText = property.initializer?.text.orEmpty()
        val isCacheProperty = cacheTokens.any { token ->
            typeText.contains(token) || initText.contains(token)
        }
        if (!isCacheProperty) return

        if (property.hasOptOutComment(optOutMarker)) return

        reportElement(
            property,
            "Ad-hoc in-memory cache detected in $className: '${property.name}'. " +
                "Storing state in ad-hoc in-memory collections creates split-authority and cache invalidation desync. " +
                "Store facts in the persistence engine, bind cache lifetime to revision invalidations, or mark with '// behavior-contract: managed-cache-ok: <reason>'.",
        )
    }

    private fun isTargetClass(containingClass: KtClassOrObject): Boolean {
        val className = containingClass.name ?: return false
        if (className.endsWith("UseCase") ||
            className.endsWith("Repository") ||
            className.endsWith("RepositoryImpl")
        ) {
            return true
        }
        return containingClass.superTypeListEntries.any { entry ->
            val text = entry.text
            text.contains("Repository") || text.contains("UseCase")
        }
    }
}
