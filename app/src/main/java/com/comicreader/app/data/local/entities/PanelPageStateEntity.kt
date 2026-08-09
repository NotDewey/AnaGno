package com.comicreader.app.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Processing state is separate from panel coordinates so manual layouts stay protected. */
@Entity(
    tableName = "panel_page_states",
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
data class PanelPageStateEntity(
    val comicId: Long,
    val pageIndex: Int,
    val status: String,
    val panelCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
