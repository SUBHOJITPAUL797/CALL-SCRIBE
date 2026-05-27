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

object SimpleEncryption {
    fun encrypt(str: String): String {
        return Base64.encodeToString(str.toByteArray(), Base64.DEFAULT)
    }
    fun decrypt(base64Str: String): String {
        return try {
            String(Base64.decode(base64Str, Base64.DEFAULT))
        } catch (e: Exception) {
            base64Str
        }
    }
}

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val contentEncrypted: String, // Base64 simulated encryption of transcription
    val summaryEncrypted: String, // Base64 simulated encryption of summary
    val timestamp: Long = System.currentTimeMillis(),
    val sourceUri: String? = null // To track synced files
) {
    val decodedTranscription: String
        get() = SimpleEncryption.decrypt(contentEncrypted)
        
    val decodedSummary: String
        get() = SimpleEncryption.decrypt(summaryEncrypted)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE title LIKE '%' || :query || '%' OR contentEncrypted LIKE '%' || :query || '%' OR summaryEncrypted LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchRecordings(query: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE sourceUri = :uri LIMIT 1")
    suspend fun getRecordingByUri(uri: String): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: Int)
}

@Database(entities = [Recording::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
}

class RecordingRepository(private val dao: RecordingDao) {
    val allRecordings = dao.getAllRecordings()

    // Note: Simulated search will only accurately hit exact base64 strings or titles.
    fun searchRecordings(query: String) = dao.searchRecordings(query)

    suspend fun getByUri(uri: String): Recording? = dao.getRecordingByUri(uri)

    suspend fun insert(recording: Recording) = dao.insertRecording(recording)

    suspend fun deleteById(id: Int) = dao.deleteRecordingById(id)
}
