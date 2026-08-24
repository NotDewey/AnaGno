package com.comicreader.app.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Persistent Bubble Zoom indexing state, including pages with zero dialogue. */
@Entity(
    tableName = "bubble_page_states",
    primaryKeys = ["comicId", "pageIndex"],
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
data class BubblePageStateEntity(
    val comicId: Long,
    val pageIndex: Int,
    val status: String,
    val maskVersion: String,
    val bubbleCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
