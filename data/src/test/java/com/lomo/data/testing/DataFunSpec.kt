package com.lomo.data.testing

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec

abstract class DataFunSpec : FunSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerRoot
}
