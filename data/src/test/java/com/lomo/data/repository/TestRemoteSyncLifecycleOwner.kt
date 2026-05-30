package com.lomo.data.repository

internal fun testRemoteSyncLifecycleRunner(): RemoteSyncLifecycleRunner =
    DefaultRemoteSyncLifecycleRunner(TestSyncLifecycleExecutionOwner())

internal class TestSyncLifecycleExecutionOwner : SyncLifecycleExecutionOwner {
    val reports = mutableListOf<RemoteSyncLifecycleTelemetry>()

    override fun begin(context: RemoteSyncLifecycleContext): RemoteSyncLifecycleSession =
        DefaultRemoteSyncLifecycleSession(
            context = context,
            clock = System::currentTimeMillis,
            emit = reports::add,
        )
}
