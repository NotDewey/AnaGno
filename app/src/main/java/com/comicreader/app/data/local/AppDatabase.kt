package com.comicreader.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.comicreader.app.data.local.dao.BookmarkDao
import com.comicreader.app.data.local.dao.BubbleDao
import com.comicreader.app.data.local.dao.CollectionDao
import com.comicreader.app.data.local.dao.ComicDao
import com.comicreader.app.data.local.dao.PanelDao
import com.comicreader.app.data.local.entities.BookmarkEntity
import com.comicreader.app.data.local.entities.BubbleEntity
import com.comicreader.app.data.local.entities.CollectionComicCrossRef
import com.comicreader.app.data.local.entities.CollectionEntity
import com.comicreader.app.data.local.entities.ComicEntity
import com.comicreader.app.data.local.entities.PanelEntity
import com.comicreader.app.data.local.entities.PanelPageStateEntity

@Database(
    entities = [
        ComicEntity::class,
        BookmarkEntity::class,
        PanelEntity::class,
        PanelPageStateEntity::class,
        BubbleEntity::class,
        CollectionEntity::class,
        CollectionComicCrossRef::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun panelDao(): PanelDao
    abstract fun bubbleDao(): BubbleDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        const val DATABASE_NAME = "comic_reader.db"
    }
}