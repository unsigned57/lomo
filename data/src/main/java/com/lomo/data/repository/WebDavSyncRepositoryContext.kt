package com.lomo.data.repository
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.WebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.webdav.WebDavEndpointResolver
import com.lomo.domain.model.WebDavSyncState
import kotlinx.coroutines.flow.MutableStateFlow
internal const val WEBDAV_ROOT = "lomo"
internal const val WEBDAV_MEMO_SUFFIX = ".md"
internal const val WEBDAV_MARKDOWN_CONTENT_TYPE = "text/markdown; charset=utf-8"
internal const val WEBDAV_UNKNOWN_ERROR_MESSAGE = "unknown error"
internal const val WEBDAV_FINGERPRINT_CONCURRENCY = 8
class WebDavSyncStateHolder {
        val state = MutableStateFlow<WebDavSyncState>(WebDavSyncState.Idle)
    }
class WebDavSyncRepositoryContext
constructor(
        val dataStore: LomoDataStore,
        val credentialStore: WebDavCredentialStore,
        val endpointResolver: WebDavEndpointResolver,
        val clientFactory: WebDavClientFactory,
        val markdownStorageDataSource: MarkdownStorageDataSource,
        val localMediaSyncStore: LocalMediaSyncStore,
        val metadataDao: WebDavSyncMetadataDao,
        val memoSynchronizer: MemoSynchronizer,
        val planner: WebDavSyncPlanner,
        val performanceTuner: SyncPerformanceTuner = DisabledSyncPerformanceTuner,
        val stateHolder: WebDavSyncStateHolder,
    )
