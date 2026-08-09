package com.comicreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.comicreader.app.data.local.entities.CollectionComicCrossRef
import com.comicreader.app.data.local.entities.CollectionEntity
import com.comicreader.app.data.local.entities.ComicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Query(
        """
        SELECT * FROM comic_collections
        ORDER BY dateUpdated DESC, id DESC
        """
    )
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query(
        """
        SELECT * FROM comic_collections
        WHERE id = :collectionId
        LIMIT 1
        """
    )
    fun observeCollection(
        collectionId: Long
    ): Flow<CollectionEntity?>

    @Query(
        """
        SELECT comics.* FROM comics
        INNER JOIN collection_comics
            ON comics.id = collection_comics.comicId
        WHERE collection_comics.collectionId = :collectionId
        ORDER BY collection_comics.position ASC
        """
    )
    fun observeComicsForCollection(
        collectionId: Long
    ): Flow<List<ComicEntity>>

    @Insert
    suspend fun insertCollection(
        collection: CollectionEntity
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemberships(
        items: List<CollectionComicCrossRef>
    )

    @Query(
        """
        SELECT comicId FROM collection_comics
        WHERE collectionId = :collectionId
        """
    )
    suspend fun getComicIdsForCollection(
        collectionId: Long
    ): List<Long>

    @Query(
        """
        SELECT MAX(position) FROM collection_comics
        WHERE collectionId = :collectionId
        """
    )
    suspend fun getMaximumPosition(
        collectionId: Long
    ): Int?

    @Query(
        """
        UPDATE comic_collections
        SET dateUpdated = :updatedAt
        WHERE id = :collectionId
        """
    )
    suspend fun touch(
        collectionId: Long,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE comic_collections
        SET name = :name, dateUpdated = :updatedAt
        WHERE id = :collectionId
        """
    )
    suspend fun rename(
        collectionId: Long,
        name: String,
        updatedAt: Long
    )

    @Query(
        "DELETE FROM comic_collections " +
                "WHERE id IN (:collectionIds)"
    )
    suspend fun deleteByIds(
        collectionIds: List<Long>
    )
}