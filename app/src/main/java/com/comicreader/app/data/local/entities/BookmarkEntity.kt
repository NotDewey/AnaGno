package com.comicreader.app.data.local.entities

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.comicreader.app.domain.model.Bookmark
import com.comicreader.app.domain.model.Bubble
import com.comicreader.app.domain.model.Panel

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("comicId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    val pageIndex: Int,
    val label: String? = null,
    val dateCreated: Long = System.currentTimeMillis()
)

fun BookmarkEntity.toDomain() = Bookmark(id, comicId, pageIndex, label, dateCreated)
fun Bookmark.toEntity() = BookmarkEntity(id, comicId, pageIndex, label, dateCreated)

/**
 * Guided View panel coordinates (V2). Included in the schema from day one so the
 * database doesn't need a breaking migration when Guided View ships — the reader
 * simply ignores this table until it has rows.
 */
@Entity(
    tableName = "panels",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("comicId"), Index("comicId", "pageIndex")]
)
data class PanelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    val pageIndex: Int,
    val order: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

fun PanelEntity.toDomain() = Panel(id, comicId, pageIndex, order, left, top, right, bottom)
fun Panel.toEntity() = PanelEntity(id, comicId, pageIndex, order, left, top, right, bottom)

@Entity(
    tableName = "bubbles",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("comicId"), Index(value = ["comicId", "pageIndex"], unique = false)]
)
data class BubbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val comicId: Long,
    val pageIndex: Int,
    val order: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
    @ColumnInfo(defaultValue = "''") val maskPath: String,
    @ColumnInfo(defaultValue = "1") val confidence: Float,
    @ColumnInfo(defaultValue = "0") val isManual: Boolean
)

fun BubbleEntity.toDomain() = Bubble(
    id, comicId, pageIndex, order, left, top, right, bottom,
    text, maskPath, confidence, isManual
)
fun Bubble.toEntity() = BubbleEntity(
    id, comicId, pageIndex, order, left, top, right, bottom,
    text, maskPath, confidence, isManual
)