package com.lomo.ui.testing

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec

abstract class UiComponentsFunSpec : FunSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerRoot
}
