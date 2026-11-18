package com.lomo.domain.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: persist a complete, validated quick-ball mapping in the shared settings schema;
 * owning layer: domain; priority: P1.
 * Scenarios:
 * - Given a first launch, when the catalog supplies defaults, then all five gestures have the
 *   documented actions and clicking remains new memo.
 * - Given malformed, incomplete or unknown bindings, when imported, then validation rejects them.
 * Observable outcomes: catalog default bytes and rejected storage values.
 * TDD proof: RED ./kotlin test --include-module=domain --include-classes='*QuickBallConfigTest'
 * fails because the editor.quickBallConfig descriptor is absent; GREEN recorded after implementation.
 * GREEN: ./kotlin test --include-module=app --include-module=data --include-module=domain
 * --include-module=ui-components; all 2598 discovered tests passed (including both domain targets),
 * evidence: /tmp/lomo-quick-ball-final-tests.stdout.
 * Excludes: pointer delivery, Android permissions and DataStore disk durability.
 */
class QuickBallConfigTest :
    FunSpec({
        test("given first launch when quick ball settings are read then the five default bindings are present") {
            val descriptor = SettingsCatalog.descriptors.single { it.id == "editor.quickBallConfig" }
            descriptor.defaultValue shouldBe SettingValue.Text("v1|RECORD|SCROLL_TOP|SEARCH|LONG_TEXT|FILTER")
        }

        test("given malformed or retired quick ball bindings when imported then the setting is rejected") {
            val descriptor = SettingsCatalog.descriptors.single { it.id == "editor.quickBallConfig" }
            listOf(
                "",
                "v2|RECORD|SCROLL_TOP|SEARCH|LONG_TEXT|FILTER",
                "v1|RECORD",
                "v1|UNKNOWN|SCROLL_TOP|SEARCH|LONG_TEXT|FILTER",
                "v1|TASKS|SCROLL_TOP|SEARCH|LONG_TEXT|FILTER",
            )
                .forEach { value ->
                    shouldThrow<IllegalArgumentException> { descriptor.parseStorageValue(value) }
                }
        }

        test("given remapped gestures when encoded and decoded then bindings and the fixed tap survive") {
            val config = QuickBallConfig.defaults()
                .withAction(QuickBallGesture.UP, QuickBallAction.NONE)
                .withAction(QuickBallGesture.DOWN, QuickBallAction.RECORD)
            QuickBallConfig.parse(config.storageValue) shouldBe config
            config.action(QuickBallGesture.TAP) shouldBe QuickBallAction.NEW_MEMO
            config.action(QuickBallGesture.UP) shouldBe QuickBallAction.NONE
            config.action(QuickBallGesture.DOWN) shouldBe QuickBallAction.RECORD
            config.action(QuickBallGesture.LONG_PRESS) shouldBe QuickBallAction.RECORD
            shouldThrow<IllegalStateException> {
                config.withAction(QuickBallGesture.TAP, QuickBallAction.NONE)
            }
        }
    })
