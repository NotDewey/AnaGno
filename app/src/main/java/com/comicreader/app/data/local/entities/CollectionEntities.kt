package com.comicreader.app.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.ComicCollection
import com.comicreader.app.domain.model.CollectionLayoutStyle

@Entity(
    tableName = "comic_collections",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["coverComicId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("coverComicId")]
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverComicId: Long? = null,
    val layoutStyle: String =
        CollectionLayoutStyle.HAND_FAN.name,
    val dateCreated: Long = System.currentTimeMillis(),
    val dateUpdated: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "collection_comics",
    primaryKeys = ["collectionId", "comicId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("comicId")]
)
data class CollectionComicCrossRef(
    val collectionId: Long,
    val comicId: Long,
    val position: Int
)

fun CollectionEntity.toDomain(comics: List<Comic>) = ComicCollection(
    id = id,
    name = name,
    comics = comics,
    coverComicId = coverComicId,
    layoutStyle = runCatching {
        CollectionLayoutStyle.valueOf(layoutStyle)
    }.getOrDefault(CollectionLayoutStyle.HAND_FAN),
    dateCreated = dateCreated,
    dateUpdated = dateUpdated
)