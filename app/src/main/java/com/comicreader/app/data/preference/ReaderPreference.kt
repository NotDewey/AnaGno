package com.comicreader.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.comicreader.app.ui.reader.ReadingDirection
import com.comicreader.app.ui.reader.ReadingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readerPreferencesDataStore by preferencesDataStore(
    name = "reader_preferences"
)

@Singleton
class ReaderPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun readingMode(comicId: Long): Flow<ReadingMode> =
        context.readerPreferencesDataStore.data.map { preferences ->
            preferences[readingModeKey(comicId)]
                ?.let { stored -> ReadingMode.entries.firstOrNull { it.name == stored } }
                ?: ReadingMode.HORIZONTAL_PAGES
        }

    fun readingDirection(comicId: Long): Flow<ReadingDirection> =
        context.readerPreferencesDataStore.data.map { preferences ->
            preferences[readingDirectionKey(comicId)]
                ?.let { stored -> ReadingDirection.entries.firstOrNull { it.name == stored } }
                ?: ReadingDirection.LEFT_TO_RIGHT
        }

    suspend fun saveReadingMode(comicId: Long, mode: ReadingMode) {
        context.readerPreferencesDataStore.edit { preferences ->
            preferences[readingModeKey(comicId)] = mode.name
        }
    }

    suspend fun saveReadingDirection(comicId: Long, direction: ReadingDirection) {
        context.readerPreferencesDataStore.edit { preferences ->
            preferences[readingDirectionKey(comicId)] = direction.name
        }
    }

    private fun readingModeKey(comicId: Long) =
        stringPreferencesKey("reading_mode_$comicId")

    private fun readingDirectionKey(comicId: Long) =
        stringPreferencesKey("reading_direction_$comicId")
}