package com.lomo.detektrules

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class LomoTestStyleRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("lomo-test-style")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf(
                RuleName("NoPerTestInitBlock") to ::NoPerTestInitBlockRule,
                RuleName("ForbiddenTestStackImports") to ::ForbiddenTestStackImportsRule,
                RuleName("MockedStatefulCollaborator") to ::MockedStatefulCollaboratorRule,
                RuleName("NoDispatchersSetMainInTests") to ::NoDispatchersSetMainInTestsRule,
                RuleName("NoRelaxedMockk") to ::NoRelaxedMockkRule,
                RuleName("NoThreadSleepInTests") to ::NoThreadSleepInTestsRule,
                RuleName("NoInteractionOnlyTest") to ::NoInteractionOnlyTestRule,
                RuleName("NoFlowFirstForStateSequence") to ::NoFlowFirstForStateSequenceRule,
                RuleName("ExcessiveMockStubbing") to ::ExcessiveMockStubbingRule,
                RuleName("NoSourceStringBehaviorTest") to ::NoSourceStringBehaviorTestRule,
            ),
        )
}
