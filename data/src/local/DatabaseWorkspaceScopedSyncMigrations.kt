package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

internal fun recreateWorkspaceScopedSyncStateTables(db: SQLiteConnection) {
    dropAndCreateWebDavSyncMetadataTable(db)
    dropAndCreateWebDavLocalFingerprintTable(db)
    dropAndCreateWebDavLocalChangeJournalTable(db)
    dropAndCreateS3SyncMetadataTable(db)
    dropAndCreateS3RemoteIndexTable(db)
    dropAndCreateS3RemoteShardStateTable(db)
    dropAndCreateS3SyncProtocolStateTable(db)
    dropAndCreateS3LocalChangeJournalTable(db)
    dropAndCreatePendingSyncConflictTable(db)
    dropAndCreatePendingSyncReviewTable(db)
}

internal fun dropAndCreateWebDavSyncMetadataTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$WEBDAV_SYNC_METADATA_TABLE`")
    createWebDavSyncMetadataTable(db)
}

private fun dropAndCreateWebDavLocalFingerprintTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `webdav_local_fingerprint`")
    createWebDavLocalFingerprintTable(db)
}

private fun dropAndCreateWebDavLocalChangeJournalTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `webdav_local_change_journal`")
    createWebDavLocalChangeJournalTable(db)
}

internal fun dropAndCreateS3SyncMetadataTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$S3_SYNC_METADATA_TABLE`")
    createS3SyncMetadataTable(db)
}

private fun dropAndCreateS3RemoteIndexTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$S3_REMOTE_INDEX_TABLE`")
    createS3RemoteIndexTable(db)
}

private fun dropAndCreateS3RemoteShardStateTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$S3_REMOTE_SHARD_STATE_TABLE`")
    createS3RemoteShardStateTable(db)
    addS3RemoteShardTelemetryColumns(db)
}

internal fun dropAndCreateS3SyncProtocolStateTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$S3_SYNC_PROTOCOL_STATE_TABLE`")
    createS3SyncProtocolStateTable(db)
}

private fun dropAndCreateS3LocalChangeJournalTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `$S3_LOCAL_CHANGE_JOURNAL_TABLE`")
    createS3LocalChangeJournalTable(db)
}

private fun dropAndCreatePendingSyncConflictTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `pending_sync_conflict`")
    createPendingSyncConflictTable(db)
}

private fun dropAndCreatePendingSyncReviewTable(db: SQLiteConnection) {
    db.execSQL("$DROP_TABLE_IF_EXISTS `pending_sync_review`")
    createPendingSyncReviewTable(db)
}
