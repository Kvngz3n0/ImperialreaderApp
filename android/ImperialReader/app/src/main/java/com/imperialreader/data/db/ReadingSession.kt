package com.imperialreader.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

@Entity(
    tableName = "reading_sessions",
    indices = [Index("startedAt"), Index("mangaId")]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaKind: String,
    val mangaId: Long,
    val chapterId: Long,
    val startedAt: Long,
    val durationMs: Long,
    val units: Int
)

@Dao
interface ReadingSessionDao {
    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Update
    suspend fun update(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query("DELETE FROM reading_sessions")
    suspend fun clearAll()

    @Query("SELECT * FROM manga")
    suspend fun getAllManga(): List<MangaEntity>

    @Query("SELECT * FROM chapters WHERE read = 1")
    suspend fun getCompletedChapters(): List<ChapterEntity>
}
