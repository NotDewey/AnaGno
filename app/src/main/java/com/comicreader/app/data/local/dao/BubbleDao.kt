package com.comicreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.comicreader.app.data.local.entities.BubbleEntity
import com.comicreader.app.data.local.entities.BubblePageStateEntity

@Dao
interface BubbleDao {
    @Query("SELECT * FROM bubbles WHERE comicId = :comicId AND pageIndex = :pageIndex ORDER BY `order`")
    suspend fun getForPage(comicId: Long, pageIndex: Int): List<BubbleEntity>

    @Insert
    suspend fun insertAll(bubbles: List<BubbleEntity>)

    @Query("DELETE FROM bubbles WHERE comicId = :comicId AND pageIndex = :pageIndex")
    suspend fun deleteForPage(comicId: Long, pageIndex: Int)

    @Query("SELECT * FROM bubble_page_states WHERE comicId = :comicId AND pageIndex = :pageIndex")
    suspend fun getState(comicId: Long, pageIndex: Int): BubblePageStateEntity?

    @Query("SELECT * FROM bubble_page_states WHERE comicId = :comicId")
    suspend fun getStatesForComic(comicId: Long): List<BubblePageStateEntity>

    @Upsert
    suspend fun upsertState(state: BubblePageStateEntity)

    @Transaction
    suspend fun replaceForPage(comicId: Long, pageIndex: Int, bubbles: List<BubbleEntity>) {
        deleteForPage(comicId, pageIndex)
        if (bubbles.isNotEmpty()) insertAll(bubbles)
    }

    @Transaction
    suspend fun replaceIndexedPage(
        comicId: Long,
        pageIndex: Int,
        bubbles: List<BubbleEntity>,
        state: BubblePageStateEntity
    ) {
        deleteForPage(comicId, pageIndex)
        if (bubbles.isNotEmpty()) insertAll(bubbles)
        upsertState(state)
    }
}
