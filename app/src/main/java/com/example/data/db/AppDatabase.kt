package com.example.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcriptions")
data class TranscriptionSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amharicText: String,
    val englishTranslation: String,
    val durationSeconds: Int,
    val filePath: String?,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val role: String, // "user" or "model"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "flashcard_mastery")
data class FlashcardMasteryEntity(
    @PrimaryKey val cardId: Int,
    val isMastered: Boolean,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "quiz_scores")
data class QuizScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val score: Int,
    val totalQuestions: Int,
    val percentage: Int = if (totalQuestions > 0) (score * 100) / totalQuestions else 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "voice_recordings")
data class VoiceRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val filePath: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscription(session: TranscriptionSession): Long

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteTranscriptionById(id: Int)

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllChatMessages(): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatHistory()

    // Flashcard mastery persistence
    @Query("SELECT * FROM flashcard_mastery")
    fun getAllFlashcardMastery(): Flow<List<FlashcardMasteryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFlashcardMastery(mastery: FlashcardMasteryEntity)

    // Quiz scores persistence
    @Query("SELECT * FROM quiz_scores ORDER BY timestamp DESC")
    fun getAllQuizScores(): Flow<List<QuizScoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuizScore(score: QuizScoreEntity): Long

    @Query("SELECT * FROM quiz_scores ORDER BY timestamp DESC LIMIT 1")
    fun getLatestQuizScore(): Flow<QuizScoreEntity?>

    @Query("SELECT MAX(score) FROM quiz_scores")
    fun getBestQuizScore(): Flow<Int?>

    // Saved voice recordings library persistence
    @Query("SELECT * FROM voice_recordings ORDER BY createdAt DESC")
    fun getAllVoiceRecordings(): Flow<List<VoiceRecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceRecording(recording: VoiceRecordingEntity): Long

    @Query("UPDATE voice_recordings SET name = :newName WHERE id = :id")
    suspend fun renameVoiceRecording(id: Int, newName: String)

    @Query("DELETE FROM voice_recordings WHERE id = :id")
    suspend fun deleteVoiceRecordingById(id: Int)

    @Query("DELETE FROM voice_recordings WHERE filePath = :filePath")
    suspend fun deleteVoiceRecordingByPath(filePath: String)

    @Query("SELECT * FROM voice_recordings WHERE filePath = :filePath LIMIT 1")
    suspend fun getVoiceRecordingByPath(filePath: String): VoiceRecordingEntity?
}

@Database(
    entities = [
        TranscriptionSession::class,
        ChatMessage::class,
        FlashcardMasteryEntity::class,
        QuizScoreEntity::class,
        VoiceRecordingEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flashcard_mastery` (
                        `cardId` INTEGER NOT NULL,
                        `isMastered` INTEGER NOT NULL,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`cardId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `quiz_scores` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `score` INTEGER NOT NULL,
                        `totalQuestions` INTEGER NOT NULL,
                        `percentage` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `voice_recordings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
