package com.comicreader.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.comicreader.app.data.cbz.CbzExtractor
import com.comicreader.app.data.local.AppDatabase
import com.comicreader.app.data.local.dao.BookmarkDao
import com.comicreader.app.data.local.dao.BubbleDao
import com.comicreader.app.data.local.dao.CollectionDao
import com.comicreader.app.data.local.dao.ComicDao
import com.comicreader.app.data.local.dao.PanelDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `panel_page_states` (
                    `comicId` INTEGER NOT NULL,
                    `pageIndex` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `panelCount` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `errorMessage` TEXT,
                    PRIMARY KEY(`comicId`, `pageIndex`),
                    FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_panel_page_states_comicId` " +
                        "ON `panel_page_states` (`comicId`)"
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO `panel_page_states`
                    (`comicId`, `pageIndex`, `status`, `panelCount`, `updatedAt`, `errorMessage`)
                SELECT `comicId`, `pageIndex`, 'MANUAL', COUNT(*),
                    CAST(strftime('%s','now') AS INTEGER) * 1000, NULL
                FROM `panels`
                GROUP BY `comicId`, `pageIndex`
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bubbles` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `comicId` INTEGER NOT NULL,
                    `pageIndex` INTEGER NOT NULL,
                    `order` INTEGER NOT NULL,
                    `left` REAL NOT NULL,
                    `top` REAL NOT NULL,
                    `right` REAL NOT NULL,
                    `bottom` REAL NOT NULL,
                    `text` TEXT NOT NULL,
                    FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bubbles_comicId` " +
                        "ON `bubbles` (`comicId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bubbles_comicId_pageIndex` " +
                        "ON `bubbles` (`comicId`, `pageIndex`)"
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `bubbles` " +
                        "ADD COLUMN `maskPath` TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "ALTER TABLE `bubbles` " +
                        "ADD COLUMN `confidence` REAL NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE `bubbles` " +
                        "ADD COLUMN `isManual` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `comic_collections` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `coverComicId` INTEGER,
                    `dateCreated` INTEGER NOT NULL,
                    `dateUpdated` INTEGER NOT NULL,
                    FOREIGN KEY(`coverComicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_comic_collections_coverComicId` " +
                        "ON `comic_collections` (`coverComicId`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `collection_comics` (
                    `collectionId` INTEGER NOT NULL,
                    `comicId` INTEGER NOT NULL,
                    `position` INTEGER NOT NULL,
                    PRIMARY KEY(`collectionId`, `comicId`),
                    FOREIGN KEY(`collectionId`) REFERENCES `comic_collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_collection_comics_comicId` " +
                        "ON `collection_comics` (`comicId`)"
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `comic_collections` " +
                        "ADD COLUMN `layoutStyle` TEXT NOT NULL " +
                        "DEFAULT 'HAND_FAN'"
            )
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `comics` " +
                        "ADD COLUMN `isFinished` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE `comics` " +
                        "ADD COLUMN `finishedAt` INTEGER"
            )
            db.execSQL(
                "ALTER TABLE `comics` " +
                        "ADD COLUMN `userRating` REAL"
            )
            db.execSQL(
                "ALTER TABLE `comics` " +
                        "ADD COLUMN `ratedAt` INTEGER"
            )

            /*
             * Preserve meaningful progress from existing installations. A
             * comic already left on its final page becomes completed.
             */
            db.execSQL(
                """
                UPDATE `comics`
                SET `isFinished` = 1,
                    `finishedAt` = COALESCE(`dateLastOpened`, `dateAdded`)
                WHERE `dateLastOpened` IS NOT NULL
                  AND `pageCount` > 0
                  AND `lastReadPage` >= (`pageCount` - 1)
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bubble_page_states` (
                    `comicId` INTEGER NOT NULL,
                    `pageIndex` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `maskVersion` TEXT NOT NULL,
                    `bubbleCount` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `errorMessage` TEXT,
                    PRIMARY KEY(`comicId`, `pageIndex`),
                    FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bubble_page_states_comicId` " +
                        "ON `bubble_page_states` (`comicId`)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8
            )
            .build()

    @Provides
    fun provideComicDao(db: AppDatabase): ComicDao = db.comicDao()

    @Provides
    fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun providePanelDao(db: AppDatabase): PanelDao = db.panelDao()

    @Provides
    fun provideBubbleDao(db: AppDatabase): BubbleDao = db.bubbleDao()

    @Provides
    fun provideCollectionDao(db: AppDatabase): CollectionDao =
        db.collectionDao()

    @Provides
    @Singleton
    fun provideCbzExtractor(
        @ApplicationContext context: Context
    ): CbzExtractor = CbzExtractor(context)
}