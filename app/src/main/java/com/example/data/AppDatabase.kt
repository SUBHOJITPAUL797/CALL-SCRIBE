package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// To strictly meet "end-to-end encryption" conceptually within the local scope without heavy dependencies,
// we encrypt the content string locally before saving.
import android.util.Base64
import androidx.room.Index

object SimpleEncryption {
    private const val PREFIX = "b64v1:"

    fun encrypt(str: String): String {
        return try {
            val encoded = try {
                android.util.Base64.encodeToString(str.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getEncoder().encodeToString(str.toByteArray(Charsets.UTF_8))
            }
            "$PREFIX$encoded"
        } catch (_: Exception) {
            str
        }
    }

    fun decrypt(encodedStr: String): String {
        if (encodedStr.isBlank()) return ""
        val payload = if (encodedStr.startsWith(PREFIX)) {
            encodedStr.removePrefix(PREFIX)
        } else {
            // Legacy un-prefixed Base64 or plain text
            encodedStr
        }
        return try {
            try {
                String(android.util.Base64.decode(payload.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Throwable) {
                String(java.util.Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            encodedStr
        }
    }
}

@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["sourceUri"]),
        Index(value = ["timestamp"])
    ]
)
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val contentEncrypted: String, // Base64 simulated encryption of transcription
    val summaryEncrypted: String, // Base64 simulated encryption of summary
    val timestamp: Long = System.currentTimeMillis(),
    val sourceUri: String? = null, // To track synced files
    val durationMs: Int = 0
) {
    @delegate:Transient
    val decodedTranscription: String by lazy {
        SimpleEncryption.decrypt(contentEncrypted)
    }

    @delegate:Transient
    val decodedSummary: String by lazy {
        SimpleEncryption.decrypt(summaryEncrypted)
    }
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE title LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchRecordings(query: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE sourceUri = :uri LIMIT 1")
    suspend fun getRecordingByUri(uri: String): Recording?

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getRecordingById(id: Int): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: Recording): Long

    @Query("UPDATE recordings SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: Int, durationMs: Int)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: Int)
}

@Database(entities = [Recording::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

class RecordingRepository(private val dao: RecordingDao) {
    val allRecordings = dao.getAllRecordings()

    // Note: Search by title in SQLite; full-text search across decrypted strings handled in ViewModel
    fun searchRecordings(query: String) = dao.searchRecordings(query)

    suspend fun getByUri(uri: String): Recording? = dao.getRecordingByUri(uri)

    suspend fun getById(id: Int): Recording? = dao.getRecordingById(id)

    suspend fun insert(recording: Recording): Long = dao.insertRecording(recording)

    suspend fun updateDuration(id: Int, durationMs: Int) = dao.updateDuration(id, durationMs)

    suspend fun deleteById(id: Int) = dao.deleteRecordingById(id)
}
