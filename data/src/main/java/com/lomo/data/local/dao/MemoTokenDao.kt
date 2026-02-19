package com.lomo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoTokenEntity

@Dao
interface MemoTokenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tokens: List<MemoTokenEntity>)

    @Query("DELETE FROM memo_token WHERE memoId = :memoId")
    suspend fun deleteByMemoId(memoId: String)

    // 简单模糊：token 前缀匹配，或完整包含匹配（按需选择）
    @Query(
        """
        SELECT Lomo.* FROM memo_token 
        JOIN Lomo ON memo_token.memoId = Lomo.id
        WHERE Lomo.isDeleted = 0 AND memo_token.token LIKE :prefix || '%'
        GROUP BY Lomo.id
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun searchByTokenPrefix(prefix: String): PagingSource<Int, MemoEntity>

    // 多词 AND 查询（最多 5 个词）
    fun searchByTokensAnd(tokens: List<String>): PagingSource<Int, MemoEntity> =
        when (tokens.size) {
            0 -> throw IllegalArgumentException("empty tokens")
            1 -> searchByTokenPrefix(tokens[0])
            2 -> search2(tokens[0], tokens[1])
            3 -> search3(tokens[0], tokens[1], tokens[2])
            4 -> search4(tokens[0], tokens[1], tokens[2], tokens[3])
            else -> search5(tokens[0], tokens[1], tokens[2], tokens[3], tokens[4])
        }

    @Query(
        """
        SELECT Lomo.* FROM Lomo
        WHERE Lomo.isDeleted = 0 AND Lomo.id IN (
            SELECT memoId FROM memo_token WHERE token LIKE :t1 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t2 || '%'
        )
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun search2(t1: String, t2: String): PagingSource<Int, MemoEntity>

    @Query(
        """
        SELECT Lomo.* FROM Lomo
        WHERE Lomo.isDeleted = 0 AND Lomo.id IN (
            SELECT memoId FROM memo_token WHERE token LIKE :t1 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t2 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t3 || '%'
        )
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun search3(t1: String, t2: String, t3: String): PagingSource<Int, MemoEntity>

    @Query(
        """
        SELECT Lomo.* FROM Lomo
        WHERE Lomo.isDeleted = 0 AND Lomo.id IN (
            SELECT memoId FROM memo_token WHERE token LIKE :t1 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t2 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t3 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t4 || '%'
        )
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun search4(t1: String, t2: String, t3: String, t4: String): PagingSource<Int, MemoEntity>

    @Query(
        """
        SELECT Lomo.* FROM Lomo
        WHERE Lomo.isDeleted = 0 AND Lomo.id IN (
            SELECT memoId FROM memo_token WHERE token LIKE :t1 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t2 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t3 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t4 || '%'
            INTERSECT
            SELECT memoId FROM memo_token WHERE token LIKE :t5 || '%'
        )
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun search5(t1: String, t2: String, t3: String, t4: String, t5: String): PagingSource<Int, MemoEntity>
}

