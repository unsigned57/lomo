package com.lomo.app.testing

import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.listeners.BeforeEachListener
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : BeforeEachListener, AfterEachListener {
    override suspend fun beforeEach(testCase: TestCase) {
        Dispatchers.setMain(testDispatcher)
    }

    override suspend fun afterEach(
        testCase: TestCase,
        result: TestResult,
    ) {
        Dispatchers.resetMain()
    }
}
