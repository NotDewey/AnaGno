package com.comicreader.app.data.local.dao

import androidx.room.*
import com.comicreader.app.data.local.entities.ComicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {

    @Query("SELECT * FROM comics ORDER BY dateAdded DESC")
    fun observeAll(): Flow<List<ComicEntity>>


    @Query(
        "SELECT * FROM comics " +
                "WHERE dateLastOpened IS NOT NULL AND isFinished = 0 " +
                "ORDER BY dateLastOpened DESC"
    )
    fun observeCurrentlyReading(): Flow<List<ComicEntity>>

    @Query(
        "UPDATE comics SET dateLastOpened = NULL " +
                "WHERE id = :comicId"
    )
    suspend fun removeFromCurrentlyReading(comicId: Long)

    @Query(
        "SELECT * FROM comics " +
                "WHERE isFinished = 1 AND userRating IS NOT NULL " +
                "ORDER BY userRating DESC, ratedAt DESC"
    )
    fun observeFinishedAndRated(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comics WHERE id = :id")
    suspend fun getById(id: Long): ComicEntity?

    @Query("SELECT * FROM comics")
    suspend fun getAll(): List<ComicEntity>

    @Query("SELECT * FROM comics WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): ComicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comic: ComicEntity): Long

    @Update
    suspend fun update(comic: ComicEntity)

    @Query("UPDATE comics SET lastReadPage = :page, dateLastOpened = :openedAt WHERE id = :comicId")
    suspend fun updateProgress(comicId: Long, page: Int, openedAt: Long = System.currentTimeMillis())

    @Query("UPDATE comics SET isFavorite = :isFavorite WHERE id = :comicId")
    suspend fun setFavorite(comicId: Long, isFavorite: Boolean)

    @Query(
        "UPDATE comics SET isFinished = 1, " +
                "finishedAt = COALESCE(finishedAt, :finishedAt) " +
                "WHERE id = :comicId"
    )
    suspend fun markFinished(
        comicId: Long,
        finishedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE comics SET isFinished = 0, finishedAt = NULL " +
                "WHERE id = :comicId"
    )
    suspend fun markUnfinished(comicId: Long)

    @Query(
        "UPDATE comics SET userRating = :rating, ratedAt = :ratedAt " +
                "WHERE id = :comicId"
    )
    suspend fun setRating(
        comicId: Long,
        rating: Float,
        ratedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE comics SET userRating = NULL, ratedAt = NULL " +
                "WHERE id = :comicId"
    )
    suspend fun clearRating(comicId: Long)


    @Query("UPDATE comics SET title = :title WHERE id = :comicId")
    suspend fun rename(comicId: Long, title: String)

    @Delete
    suspend fun delete(comic: ComicEntity)

    @Query("SELECT * FROM comics WHERE title LIKE '%' || :query || '%' OR series LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<ComicEntity>>
}