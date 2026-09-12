package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Detektion
import dev.detekt.api.FileProcessListener
import dev.detekt.api.Finding
import dev.detekt.api.Issue
import dev.detekt.api.RuleInstance
import dev.detekt.api.RuleSetId
import dev.detekt.api.Severity
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.psi.KtFile
import java.net.URI

/**
 * Source annotations cannot suppress the policy that prohibits source suppression itself.
 * Reuses the rule's PSI check, then publishes its findings after Detekt's annotation filtering.
 * The CLI activation contract exercises this through the real service loader, including @file:Suppress.
 */
class SourceSuppressionPolicy : FileProcessListener {
    override val id: String = "LomoSourceSuppressionPolicy"
    override fun onFinish(files: List<KtFile>, result: Detektion): Detektion {
        val rule = NoSourceSuppressionsRule(Config.empty)
        val instance = RuleInstance(
            id = "NoSourceSuppressions",
            ruleSetId = RuleSetId("lomo-architecture"),
            url = URI("AGENTS.md"),
            description = rule.description,
            severity = Severity.Error,
            active = true,
        )
        val findings = files.flatMap { file -> rule.visitFile(file, LanguageVersionSettingsImpl.DEFAULT) }
        return Detektion(
            issues = result.issues.filterNot { it.ruleInstance.id == instance.id } +
                findings.map { it.enforcedIssue(instance) },
            rules = result.rules.filterNot { it.id == instance.id } + instance,
            notifications = result.notifications,
            metrics = result.metrics,
            userData = result.userData,
        )
    }

    private fun Finding.enforcedIssue(instance: RuleInstance): Issue {
        val location = entity.location
        return Issue(
            ruleInstance = instance,
            entity = Issue.Entity(
                entity.signature,
                Issue.Location(location.source, location.endSource, location.text, location.path),
            ),
            references = emptyList(),
            message = message,
            severity = Severity.Error,
            suppressReasons = emptyList(),
        )
    }
}
