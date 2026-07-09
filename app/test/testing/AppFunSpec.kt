package com.lomo.app.testing

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec

abstract class AppFunSpec : FunSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerRoot
}
