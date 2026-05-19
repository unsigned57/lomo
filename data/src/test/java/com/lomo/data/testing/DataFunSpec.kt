package com.lomo.data.testing

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec
import io.mockk.MockKMatcherScope
import io.mockk.MockKStubScope
import io.mockk.coEvery
import io.mockk.every

abstract class DataFunSpec : FunSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerRoot

    protected fun <T> mockEvery(block: MockKMatcherScope.() -> T): MockKStubScope<T, T> = every(stubBlock = block)
    protected fun <T> mockCoEvery(block: suspend MockKMatcherScope.() -> T): MockKStubScope<T, T> = coEvery(stubBlock = block)
}

