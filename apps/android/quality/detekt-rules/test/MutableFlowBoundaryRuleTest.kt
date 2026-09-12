package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: NoMutableFlowExposure through the production rule provider.
 * - Owning layer: quality; priority P0.
 * - Capability: a mutable stream has one owner; clients receive a read-only stream.
 * Scenarios:
 * - Given public/inferred/aliased/getter/constructor mutable flows, when checked, then exposing
 *   the mutation capability is rejected regardless of the owner's class name or facade marker.
 * - Given private backing state and a read-only wrapper, when checked, then it is accepted.
 * Observable outcomes: actual Detekt findings on source properties and return signatures.
 * TDD proof: before registration these scenarios fail because no rule protects state holders;
 * runtime RED/GREEN evidence is recorded in audit-09.
 * Excludes: local variables, Compose rendering state, cross-file inferred return types.
 */
class MutableFlowBoundaryRuleTest : FunSpec({
    test("a state holder cannot expose an inferred mutable flow") {
        mutableFlowFindings("class Holder { val changes = MutableStateFlow(0) }").shouldHaveSize(1)
    }
    test("a constructor property cannot expose a mutable flow") {
        mutableFlowFindings("class Holder(val changes: MutableStateFlow<Int>)").shouldHaveSize(1)
    }
    test("internal visibility still exports the writer outside its class") {
        mutableFlowFindings("class Holder { internal val changes = MutableSharedFlow<Int>() }").shouldHaveSize(1)
    }
    test("an import alias cannot hide mutable stream identity") {
        mutableFlowFindings("import kotlinx.coroutines.flow.MutableStateFlow as Signal\nclass Holder { val changes = Signal(0) }")
            .shouldHaveSize(1)
    }
    test("a same-file type alias cannot export the writer") {
        mutableFlowFindings("typealias Signal = MutableStateFlow<Int>\nclass Holder(val changes: Signal)").shouldHaveSize(1)
    }
    test("an inferred getter cannot return private mutable backing state") {
        mutableFlowFindings("class Holder { private val backing = MutableStateFlow(0); val changes get() = backing }")
            .shouldHaveSize(1)
    }
    test("functions and interfaces cannot return an explicit mutable flow") {
        mutableFlowFindings("interface Port { fun changes(): MutableStateFlow<Int> }").shouldHaveSize(1)
    }
    test("a facade exception never grants external write access") {
        mutableFlowFindings("// behavior-contract: session-facade-ok: platform recording\nclass HolderViewModel { val changes = MutableStateFlow(0) }")
            .shouldHaveSize(1)
    }
    test("private backing with a read-only wrapper preserves one writer") {
        mutableFlowFindings("class Holder { private val backing = MutableStateFlow(0); val changes = backing.asStateFlow() }")
            .shouldHaveSize(0)
    }
    test("local mutable streams do not escape by declaration") {
        mutableFlowFindings("fun collect() { val local = MutableStateFlow(0); consume(local) }").shouldHaveSize(0)
    }
})

private fun mutableFlowFindings(source: String): List<dev.detekt.api.Finding> {
    val rule = checkNotNull(LomoArchitectureRuleSetProvider().instance().rules[RuleName("NoMutableFlowExposure")]) {
        "Missing production mutable-flow ownership rule"
    }.invoke(Config.empty)
    val root = Files.createTempDirectory("lomo-mutable-flow-rule")
    try {
        val file = root.resolve("app/src/Holder.kt")
        file.parent.createDirectories()
        file.writeText(source)
        return rule.lint(compileForTest(file))
    } finally {
        check(root.toFile().deleteRecursively()) { "Cannot clean fixture $root" }
    }
}
