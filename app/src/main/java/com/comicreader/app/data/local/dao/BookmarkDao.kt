package com.comicreader.app.data.local.dao

import androidx.room.*
import com.comicreader.app.data.local.entities.BookmarkEntity
import com.comicreader.app.data.local.entities.PanelEntity
import com.comicreader.app.data.local.entities.PanelPageStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE comicId = :comicId ORDER BY pageIndex ASC")
    fun observeForComic(comicId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)
}

@Dao
interface PanelDao {
    @Query("SELECT * FROM panels WHERE comicId = :comicId AND pageIndex = :pageIndex ORDER BY `order` ASC")
    suspend fun getForPage(comicId: Long, pageIndex: Int): List<PanelEntity>

    @Query("SELECT COUNT(*) FROM panels WHERE comicId = :comicId AND pageIndex = :pageIndex")
    suspend fun countForPage(comicId: Long, pageIndex: Int): Int

    @Query("SELECT * FROM panel_page_states WHERE comicId = :comicId")
    suspend fun getStatesForComic(comicId: Long): List<PanelPageStateEntity>

    @Query("SELECT * FROM panel_page_states WHERE comicId = :comicId AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getState(comicId: Long, pageIndex: Int): PanelPageStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: PanelPageStateEntity)

    @Query(
        "SELECT pageIndex FROM panel_page_states " +
                "WHERE comicId = :comicId AND status IN ('NEEDS_REVIEW', 'FAILED') ORDER BY pageIndex"
    )
    fun observeReviewPages(comicId: Long): Flow<List<Int>>

    @Query(
        """
        SELECT comicId,
            SUM(CASE WHEN status IN ('AI_DETECTED','NEEDS_REVIEW','MANUAL','FAILED') THEN 1 ELSE 0 END) AS analyzedPages,
            SUM(CASE WHEN status IN ('NEEDS_REVIEW','FAILED') THEN 1 ELSE 0 END) AS reviewPages,
            SUM(CASE WHEN status = 'PROCESSING' THEN 1 ELSE 0 END) AS processingPages
        FROM panel_page_states
        GROUP BY comicId
        """
    )
    fun observeAllProgress(): Flow<List<PanelProgressRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(panels: List<PanelEntity>)

    @Query("DELETE FROM panels WHERE comicId = :comicId AND pageIndex = :pageIndex")
    suspend fun clearPage(comicId: Long, pageIndex: Int)

    @Transaction
    suspend fun replaceForPage(
        comicId: Long,
        pageIndex: Int,
        panels: List<PanelEntity>
    ) {
        clearPage(comicId, pageIndex)
        if (panels.isNotEmpty()) insertAll(panels)
    }

    @Transaction
    suspend fun replaceManualForPage(
        comicId: Long,
        pageIndex: Int,
        panels: List<PanelEntity>
    ) {
        replaceForPage(comicId, pageIndex, panels)
        upsertState(
            PanelPageStateEntity(
                comicId = comicId,
                pageIndex = pageIndex,
                status = "MANUAL",
                panelCount = panels.size
            )
        )
    }

    @Transaction
    suspend fun beginDetectionUnlessManual(comicId: Long, pageIndex: Int): Boolean {
        if (getState(comicId, pageIndex)?.status == "MANUAL") return false
        upsertState(
            PanelPageStateEntity(
                comicId = comicId,
                pageIndex = pageIndex,
                status = "PROCESSING"
            )
        )
        return true
    }

    @Transaction
    suspend fun replaceDetectedUnlessManual(
        comicId: Long,
        pageIndex: Int,
        panels: List<PanelEntity>,
        finalState: PanelPageStateEntity
    ): Boolean {
        if (getState(comicId, pageIndex)?.status == "MANUAL") return false
        replaceForPage(comicId, pageIndex, panels)
        upsertState(finalState)
        return true
    }

    @Transaction
    suspend fun markFailedUnlessManual(
        comicId: Long,
        pageIndex: Int,
        message: String
    ) {
        if (getState(comicId, pageIndex)?.status == "MANUAL") return
        upsertState(
            PanelPageStateEntity(
                comicId = comicId,
                pageIndex = pageIndex,
                status = "FAILED",
                errorMessage = message
            )
        )
    }
}

data class PanelProgressRow(
    val comicId: Long,
    val analyzedPages: Long,
    val reviewPages: Long,
    val processingPages: Long
)