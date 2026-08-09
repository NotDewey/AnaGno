package com.comicreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.comicreader.app.data.local.entities.BubbleEntity

@Dao
interface BubbleDao {
    @Query("SELECT * FROM bubbles WHERE comicId = :comicId AND pageIndex = :pageIndex ORDER BY `order`")
    suspend fun getForPage(comicId: Long, pageIndex: Int): List<BubbleEntity>

    @Insert
    suspend fun insertAll(bubbles: List<BubbleEntity>)

    @Query("DELETE FROM bubbles WHERE comicId = :comicId AND pageIndex = :pageIndex")
    suspend fun deleteForPage(comicId: Long, pageIndex: Int)

    @Transaction
    suspend fun replaceForPage(comicId: Long, pageIndex: Int, bubbles: List<BubbleEntity>) {
        deleteForPage(comicId, pageIndex)
        if (bubbles.isNotEmpty()) insertAll(bubbles)
    }
}
