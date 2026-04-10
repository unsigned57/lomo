package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneCryptConfig
import javax.inject.Inject
import javax.inject.Singleton

internal const val S3_ROOT = "lomo"
internal const val S3_MEMO_SUFFIX = ".md"
internal const val S3_MARKDOWN_CONTENT_TYPE = "text/markdown; charset=utf-8"
internal const val S3_MTIME_METADATA_KEY = "mtime"
internal const val S3_MTIME_LEGACY_METADATA_KEY = "MTime"
internal const val S3_CTIME_METADATA_KEY = "ctime"
internal const val S3_UNKNOWN_ERROR_MESSAGE = "unknown error"

data class S3ResolvedConfig(
    val endpointUrl: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val pathStyle: S3PathStyle,
    val encryptionMode: S3EncryptionMode,
    val encryptionPassword: String?,
    val allowInsecureHttp: Boolean = false,
    val endpointProfile: S3EndpointProfile = S3EndpointProfile.GENERIC_S3,
    val encryptionPassword2: String? = null,
    val rcloneCryptConfig: S3RcloneCryptConfig = S3RcloneCryptConfig(),
)

@Singleton
class S3SyncRepositoryContext
    @Inject
    constructor(
        val dataStore: LomoDataStore,
        val credentialStore: S3CredentialStore,
        val clientFactory: LomoS3ClientFactory,
        val markdownStorageDataSource: MarkdownStorageDataSource,
        val localMediaSyncStore: LocalMediaSyncStore,
        val metadataDao: S3SyncMetadataDao,
        val memoSynchronizer: MemoSynchronizer,
        val planner: S3SyncPlanner,
        val stateHolder: S3SyncStateHolder,
        val transactionRunner: S3SyncTransactionRunner = NoOpS3SyncTransactionRunner,
    )
