package com.lomo.domain.testing

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec

abstract class DomainFunSpec : FunSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerRoot
}
