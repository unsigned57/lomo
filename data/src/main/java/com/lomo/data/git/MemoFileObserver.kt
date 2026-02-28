package com.lomo.data.git

import android.os.FileObserver
import com.lomo.data.local.datastore.LomoDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoFileObserver
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _fileChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val fileChanged: SharedFlow<Unit> = _fileChanged

        private var currentObserver: FileObserver? = null

        init {
            scope.launch {
                combine(
                    dataStore.gitSyncEnabled,
                    dataStore.gitSyncOnFileChange,
                    dataStore.rootDirectory,
                ) { enabled, fileChangeEnabled, rootDir ->
                    Triple(enabled, fileChangeEnabled, rootDir)
                }.distinctUntilChanged()
                    .collect { (enabled, fileChangeEnabled, rootDir) ->
                        stopObserver()
                        if (enabled && fileChangeEnabled && !rootDir.isNullOrBlank()) {
                            val dir = File(rootDir)
                            if (dir.exists() && dir.isDirectory) {
                                startObserver(dir)
                            }
                        }
                    }
            }
        }

        private fun startObserver(directory: File) {
            val mask = FileObserver.MODIFY or
                FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO

            val observer = object : FileObserver(directory, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    // Only react to .md files, ignore .git directory
                    if (path.startsWith(".git")) return
                    if (!path.endsWith(".md")) return

                    Timber.d("MemoFileObserver: event=%d path=%s", event, path)
                    _fileChanged.tryEmit(Unit)
                }
            }
            observer.startWatching()
            currentObserver = observer
            Timber.d("MemoFileObserver: started watching %s", directory.absolutePath)
        }

        private fun stopObserver() {
            currentObserver?.stopWatching()
            currentObserver = null
        }
    }
