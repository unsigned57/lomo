package com.lomo.app.navigation

import com.lomo.app.LaunchNavigationTarget
import com.lomo.app.WidgetDestination
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LaunchNavigationStepTest :
    FunSpec({
        test("given null current route when resolving launch navigation then waits for graph") {
            resolveLaunchNavigationStep(
                target = LaunchNavigationTarget.Main,
                currentRoute = null,
            ) shouldBe LaunchNavigationStep.WaitForGraph

            resolveLaunchNavigationStep(
                target = LaunchNavigationTarget.Widget(WidgetDestination.SEARCH),
                currentRoute = null,
            ) shouldBe LaunchNavigationStep.WaitForGraph
        }

        test("given main route when targeting main then treats navigation as already on target") {
            resolveLaunchNavigationStep(
                target = LaunchNavigationTarget.Main,
                currentRoute = NavRouteSerialNames.MAIN,
            ) shouldBe LaunchNavigationStep.AlreadyOnTarget
        }

        test("given secondary route when targeting main then returns to main") {
            listOf("settings", "search", "daily_review").forEach { route ->
                resolveLaunchNavigationStep(
                    target = LaunchNavigationTarget.Main,
                    currentRoute = route,
                ) shouldBe LaunchNavigationStep.ReturnToMain
            }
        }

        test("given settled route when targeting widget destination then navigates to mapped route") {
            resolveLaunchNavigationStep(
                target = LaunchNavigationTarget.Widget(WidgetDestination.SEARCH),
                currentRoute = NavRouteSerialNames.MAIN,
            ) shouldBe LaunchNavigationStep.Navigate(NavRoute.Search)

            resolveLaunchNavigationStep(
                target = LaunchNavigationTarget.Widget(WidgetDestination.DAILY_REVIEW),
                currentRoute = NavRouteSerialNames.MAIN,
            ) shouldBe LaunchNavigationStep.Navigate(NavRoute.DailyReview)

            resolveLaunchNavigationStep(
                target = LaunchNavigationTarget.Widget(WidgetDestination.STATISTICS),
                currentRoute = "settings",
            ) shouldBe LaunchNavigationStep.Navigate(NavRoute.Statistics)
        }
    })
