package com.lomo.data.local

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

val MIGRATION_39_40: Migration =
    object : Migration(SCHEMA_VERSION_39, SCHEMA_VERSION_40) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val db = connection
            createS3RemoteIndexTable(db)
            addS3SyncProtocolIncrementalColumns(db)
        }
    }

val MIGRATION_40_41: Migration =
    object : Migration(SCHEMA_VERSION_40, SCHEMA_VERSION_41) {
        override suspend fun migrate(connection: SQLiteConnection) {
            normalizeS3SyncProtocolStateTable(connection)
        }
    }

val MIGRATION_41_42: Migration =
    object : Migration(SCHEMA_VERSION_41, SCHEMA_VERSION_42) {
        override suspend fun migrate(connection: SQLiteConnection) {
            createS3RemoteShardStateTable(connection)
        }
    }

val MIGRATION_42_43: Migration =
    object : Migration(SCHEMA_VERSION_42, SCHEMA_VERSION_43) {
        override suspend fun migrate(connection: SQLiteConnection) {
            addS3RemoteShardTelemetryColumns(connection)
        }
    }

val MIGRATION_43_44: Migration =
    object : Migration(SCHEMA_VERSION_43, SCHEMA_VERSION_44) {
        override suspend fun migrate(connection: SQLiteConnection) {
            createLegacyPendingSyncConflictTable(connection)
        }
    }

val MIGRATION_44_45: Migration =
    object : Migration(SCHEMA_VERSION_44, SCHEMA_VERSION_45) {
        override suspend fun migrate(connection: SQLiteConnection) {
            addS3SyncMetadataPersistenceColumns(connection)
        }
    }

val MIGRATION_45_46: Migration =
    object : Migration(SCHEMA_VERSION_45, SCHEMA_VERSION_46) {
        override suspend fun migrate(connection: SQLiteConnection) {
            normalizeWebDavSyncMetadataTable(connection)
        }
    }

val MIGRATION_46_47: Migration =
    object : Migration(SCHEMA_VERSION_46, SCHEMA_VERSION_47) {
        override suspend fun migrate(connection: SQLiteConnection) {
            addGeoLocationColumn(connection)
        }
    }

val MIGRATION_47_48: Migration =
    object : Migration(SCHEMA_VERSION_47, SCHEMA_VERSION_48) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val db = connection
            createWebDavLocalFingerprintTable(db)
            createWebDavLocalChangeJournalTable(db)
        }
    }

val MIGRATION_48_49: Migration =
    object : Migration(SCHEMA_VERSION_48, SCHEMA_VERSION_49) {
        override suspend fun migrate(connection: SQLiteConnection) {
            rebuildMemoImageAttachmentTable(connection)
        }
    }

val MIGRATION_49_50: Migration =
    object : Migration(SCHEMA_VERSION_49, SCHEMA_VERSION_50) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val db = connection
            backfillMemoSearchContentTokens(db)
            rebuildMemoFts4Table(db)
        }
    }

val MIGRATION_50_51: Migration =
    object : Migration(SCHEMA_VERSION_50, SCHEMA_VERSION_51) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val db = connection
            backfillMemoSearchContentTokens(db)
            rebuildMemoFts5Table(db)
        }
    }

val MIGRATION_51_52: Migration =
    object : Migration(SCHEMA_VERSION_51, SCHEMA_VERSION_52) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val db = connection
            backfillMemoSearchContentTokens(db)
            db.execSQL(
                "ALTER TABLE `$MEMO_TABLE` ADD COLUMN `$COLUMN_SEARCH_CONTENT` " +
                    "TEXT NOT NULL DEFAULT ''",
            )
            backfillMemoSearchContentColumn(db)
            ensureMemoFtsMaintenanceTable(db)
            rebuildMemoFtsExternalContentInfrastructure(db)
            db.setMemoFtsContentVersion(CURRENT_MEMO_FTS_CONTENT_VERSION)
        }
    }

val MIGRATION_52_53: Migration =
    object : Migration(SCHEMA_VERSION_52, SCHEMA_VERSION_53) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val db = connection
            normalizeMemoFileOutboxTable(db)
            ensureS3RemoteIndexSupportingIndex(db)
            ensureS3LocalChangeJournalIndex(db)
        }
    }

val MIGRATION_53_54: Migration =
    object : Migration(SCHEMA_VERSION_53, SCHEMA_VERSION_54) {
        override suspend fun migrate(connection: SQLiteConnection) {
            createImageLocationCacheTable(connection)
        }
    }

val MIGRATION_54_55: Migration =
    object : Migration(SCHEMA_VERSION_54, SCHEMA_VERSION_55) {
        override suspend fun migrate(connection: SQLiteConnection) = Unit
    }

val MIGRATION_55_56: Migration =
    object : Migration(SCHEMA_VERSION_55, SCHEMA_VERSION_56) {
        override suspend fun migrate(connection: SQLiteConnection) {
            normalizeMemoFileOutboxTable(connection)
        }
    }

val MIGRATION_56_57: Migration =
    object : Migration(SCHEMA_VERSION_56, SCHEMA_VERSION_57) {
        override suspend fun migrate(connection: SQLiteConnection) {
            splitPendingSyncReviewTable(connection)
        }
    }

val MIGRATION_57_58: Migration =
    object : Migration(SCHEMA_VERSION_57, SCHEMA_VERSION_58) {
        override suspend fun migrate(connection: SQLiteConnection) {
            backfillMemoContentFlagColumns(connection)
        }
    }

val MIGRATION_58_59: Migration =
    object : Migration(SCHEMA_VERSION_58, SCHEMA_VERSION_59) {
        override suspend fun migrate(connection: SQLiteConnection) {
            recreateWorkspaceScopedSyncStateTables(connection)
        }
    }

val MIGRATION_59_60: Migration =
    object : Migration(SCHEMA_VERSION_59, SCHEMA_VERSION_60) {
        override suspend fun migrate(connection: SQLiteConnection) {
            backfillMemoStatisticsProjectionColumns(connection)
        }
    }

val MIGRATION_60_61: Migration =
    object : Migration(SCHEMA_VERSION_60, SCHEMA_VERSION_61) {
        override suspend fun migrate(connection: SQLiteConnection) {
            addS3SyncProtocolLocalAuditCursorColumn(connection)
        }
    }

val MIGRATION_61_62: Migration =
    object : Migration(SCHEMA_VERSION_61, SCHEMA_VERSION_62) {
        override suspend fun migrate(connection: SQLiteConnection) {
            addS3RemoteIndexContentMd5Column(connection)
        }
    }

val MIGRATION_62_63: Migration =
    object : Migration(SCHEMA_VERSION_62, SCHEMA_VERSION_63) {
        override suspend fun migrate(connection: SQLiteConnection) {
            resetMemoProjectionForPositionalIds(connection)
        }
    }
