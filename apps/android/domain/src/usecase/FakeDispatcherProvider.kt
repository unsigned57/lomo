package com.lomo.domain.usecase

import kotlinx.coroutines.CoroutineDispatcher

class FakeDispatcherProvider(
    testDispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val main: CoroutineDispatcher = testDispatcher
    override val io: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
    override val unconfined: CoroutineDispatcher = testDispatcher
}
