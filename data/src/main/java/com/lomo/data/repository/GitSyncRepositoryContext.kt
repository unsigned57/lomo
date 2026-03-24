package com.lomo.data.repository

import android.content.Context
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitMediaSyncBridge
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.GitSyncQueryTestCoordinator
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.MarkdownStorageDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal const val MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE = "Memo directory is not configured"
internal const val REPOSITORY_URL_NOT_CONFIGURED_MESSAGE = "Repository URL is not configured"
internal const val MEMO_CHANGE_DEBOUNCE_MS = 5_000L
internal const val TIMESTAMP_TOLERANCE_MS = 1_000L

@Singleton
class GitSyncRepositoryContext
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        val gitSyncEngine: GitSyncEngine,
        val credentialStore: GitCredentialStore,
        val dataStore: LomoDataStore,
        val memoSynchronizer: MemoSynchronizer,
        val safGitMirrorBridge: SafGitMirrorBridge,
        val gitMediaSyncBridge: GitMediaSyncBridge,
        val gitSyncQueryCoordinator: GitSyncQueryTestCoordinator,
        val markdownParser: MarkdownParser,
        val markdownStorageDataSource: MarkdownStorageDataSource,
    )
