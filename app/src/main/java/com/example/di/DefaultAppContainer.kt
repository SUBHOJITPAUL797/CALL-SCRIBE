package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.ApiKeyManager
import com.example.data.AppDatabase
import com.example.data.RecordingRepository
import com.example.network.GeminiRepository
import com.example.network.GitHubUpdateRepository
import com.example.network.NvidiaRepository

object DefaultAppContainer {
    @Volatile
    private var database: AppDatabase? = null

    @Volatile
    private var apiKeyManagerInstance: ApiKeyManager? = null

    fun getApiKeyManager(context: Context): ApiKeyManager {
        return apiKeyManagerInstance ?: synchronized(this) {
            apiKeyManagerInstance ?: ApiKeyManager(context.applicationContext).also { apiKeyManagerInstance = it }
        }
    }

    fun getPreferencesManager(context: Context): com.example.data.CallPreferencesManager {
        return com.example.data.CallPreferencesManager(context.applicationContext)
    }

    fun getGeminiRepository(context: Context): GeminiRepository {
        val keyManager = getApiKeyManager(context)
        return GeminiRepository(apiKeyProvider = { keyManager.getApiKey() })
    }

    fun getNvidiaRepository(context: Context): NvidiaRepository {
        val keyManager = getApiKeyManager(context)
        return NvidiaRepository(apiKeyProvider = { keyManager.getNvidiaApiKey() })
    }

    val gitHubUpdateRepository: GitHubUpdateRepository by lazy {
        GitHubUpdateRepository()
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
