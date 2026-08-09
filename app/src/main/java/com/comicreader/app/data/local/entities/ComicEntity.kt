package com.comicreader.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.comicreader.app.domain.model.Comic

@Entity(tableName = "comics")
data class ComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val uri: String,
    val coverPagePath: String?,
    val pageCount: Int,
    val lastReadPage: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val dateLastOpened: Long? = null,
    val series: String? = null,
    val isFavorite: Boolean = false,
    val isFinished: Boolean = false,
    val finishedAt: Long? = null,
    val userRating: Float? = null,
    val ratedAt: Long? = null
)

fun ComicEntity.toDomain() = Comic(
    id = id,
    title = title,
    uri = uri,
    coverPagePath = coverPagePath,
    pageCount = pageCount,
    lastReadPage = lastReadPage,
    dateAdded = dateAdded,
    dateLastOpened = dateLastOpened,
    series = series,
    isFavorite = isFavorite,
    isFinished = isFinished,
    finishedAt = finishedAt,
    userRating = userRating,
    ratedAt = ratedAt
)

fun Comic.toEntity() = ComicEntity(
    id = id,
    title = title,
    uri = uri,
    coverPagePath = coverPagePath,
    pageCount = pageCount,
    lastReadPage = lastReadPage,
    dateAdded = dateAdded,
    dateLastOpened = dateLastOpened,
    series = series,
    isFavorite = isFavorite,
    isFinished = isFinished,
    finishedAt = finishedAt,
    userRating = userRating,
    ratedAt = ratedAt
)