package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.RecordingRepository
import com.example.network.GeminiRepository

object DefaultAppContainer {
    @Volatile
    private var database: AppDatabase? = null
    
    val geminiRepository: GeminiRepository by lazy { GeminiRepository() }
    val gitHubUpdateRepository: com.example.network.GitHubUpdateRepository by lazy {
        com.example.network.GitHubUpdateRepository()
    }

    fun getRepository(context: Context): RecordingRepository {
        val currentDb = database
        if (currentDb != null) {
            return RecordingRepository(currentDb.recordingDao())
        }
        return synchronized(this) {
            val existing = database
            if (existing != null) {
                RecordingRepository(existing.recordingDao())
            } else {
                val newDb = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_scribe_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                database = newDb
                RecordingRepository(newDb.recordingDao())
            }
        }
    }
}
