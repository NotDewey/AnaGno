package com.comicreader.app.data.repository

import androidx.room.withTransaction
import com.comicreader.app.data.local.AppDatabase
import com.comicreader.app.data.local.dao.CollectionDao
import com.comicreader.app.data.local.entities.CollectionComicCrossRef
import com.comicreader.app.data.local.entities.CollectionEntity
import com.comicreader.app.data.local.entities.toDomain
import com.comicreader.app.domain.model.ComicCollection
import com.comicreader.app.domain.model.CollectionLayoutStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionRepository @Inject constructor(
    private val database: AppDatabase,
    private val collectionDao: CollectionDao
) {

    /**
     * Collection membership stores comic IDs only. Each collection observes the
     * real comics table, so cover paths and renamed library titles stay current.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCollections(): Flow<List<ComicCollection>> =
        collectionDao.observeCollections().flatMapLatest { collections ->
            if (collections.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    collections.map { collection ->
                        collectionDao
                            .observeComicsForCollection(collection.id)
                            .map { comics ->
                                collection.toDomain(
                                    comics = comics.map { comic ->
                                        comic.toDomain()
                                    }
                                )
                            }
                    }
                ) { observedCollections ->
                    observedCollections.toList()
                }
            }
        }

    /**
     * Keeps the existing collection-detail screen API working while reusing
     * the same live collection stream as the main Collections screen.
     */
    fun observeCollection(
        collectionId: Long
    ): Flow<ComicCollection?> =
        observeCollections().map { collections ->
            collections.firstOrNull { collection ->
                collection.id == collectionId
            }
        }

    suspend fun createCollection(
        name: String,
        comicIds: List<Long>,
        coverComicId: Long,
        layoutStyle: CollectionLayoutStyle
    ): Long {
        val cleanName = name.trim()
        val orderedComicIds = comicIds.distinct()

        require(cleanName.isNotEmpty()) {
            "Collection name cannot be empty"
        }
        require(orderedComicIds.isNotEmpty()) {
            "Choose at least one comic"
        }
        require(coverComicId in orderedComicIds) {
            "The cover must be one of the selected comics"
        }

        val now = System.currentTimeMillis()

        return database.withTransaction {
            val collectionId =
                collectionDao.insertCollection(
                    CollectionEntity(
                        name = cleanName,
                        coverComicId = coverComicId,
                        layoutStyle = layoutStyle.name,
                        dateCreated = now,
                        dateUpdated = now
                    )
                )

            collectionDao.insertMemberships(
                orderedComicIds.mapIndexed { index, comicId ->
                    CollectionComicCrossRef(
                        collectionId = collectionId,
                        comicId = comicId,
                        position = index
                    )
                }
            )

            collectionId
        }
    }

    suspend fun addComicsToCollection(
        collectionId: Long,
        comicIds: List<Long>
    ) {
        val requestedIds = comicIds.distinct()
        if (requestedIds.isEmpty()) return

        database.withTransaction {
            val existingIds =
                collectionDao
                    .getComicIdsForCollection(collectionId)
                    .toSet()

            val newComicIds =
                requestedIds.filterNot { comicId ->
                    comicId in existingIds
                }

            if (newComicIds.isEmpty()) {
                return@withTransaction
            }

            val firstNewPosition =
                (collectionDao.getMaximumPosition(collectionId) ?: -1) + 1

            collectionDao.insertMemberships(
                newComicIds.mapIndexed { index, comicId ->
                    CollectionComicCrossRef(
                        collectionId = collectionId,
                        comicId = comicId,
                        position = firstNewPosition + index
                    )
                }
            )

            collectionDao.touch(
                collectionId = collectionId,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun renameCollection(
        collectionId: Long,
        name: String
    ) {
        val cleanName = name.trim()

        require(cleanName.isNotEmpty()) {
            "Collection name cannot be empty"
        }

        collectionDao.rename(
            collectionId = collectionId,
            name = cleanName,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun deleteCollections(
        collectionIds: Collection<Long>
    ) {
        val uniqueIds = collectionIds.distinct()
        if (uniqueIds.isEmpty()) return

        collectionDao.deleteByIds(uniqueIds)
    }
}