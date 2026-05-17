package com.lomo.data.repository


import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.entity.S3SyncMetadataEntity
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: S3 planner metadata support
 * - Behavior focus: planner metadata reads should stay on the lightweight snapshot query and return an empty map without falling back to a full-row table scan.
 * - Observable outcomes: returned planner metadata map and absence of full-row DAO access.
 * - Red phase: Fails before the fix because an empty snapshot result still falls back to getAll(), which reintroduces the full-row metadata scan the manifest-free incremental planner is meant to avoid.
 * - Excludes: Room generated SQL, repository orchestration, and sync action execution.
 */
class S3PlannerMetadataSupportTest : DataFunSpec() {
    init {
        test("readAllPlannerMetadataByPath does not fall back to full-row scan when snapshot query is empty") { `readAllPlannerMetadataByPath does not fall back to full-row scan when snapshot query is empty`() }
    }


    private fun `readAllPlannerMetadataByPath does not fall back to full-row scan when snapshot query is empty`() =
        runTest {
            val dao =
                object : S3SyncMetadataDao {
                    override suspend fun getAll(): List<S3SyncMetadataEntity> {
                        error("planner metadata helper should not fall back to getAll()")
                    }

                    override suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot> = emptyList()

                    override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> = emptyList()

                    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> =
                        emptyList()

                    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) = Unit

                    override suspend fun deleteByRelativePath(relativePath: String) = Unit

                    override suspend fun deleteByRelativePaths(relativePaths: List<String>) = Unit

                    override suspend fun clearAll() = Unit
                }

            dao.readAllPlannerMetadataByPath() shouldBe emptyMap<String, S3SyncMetadataEntity>()
        }
}
