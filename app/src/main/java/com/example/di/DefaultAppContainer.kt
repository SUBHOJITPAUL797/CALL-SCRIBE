package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.RecordingRepository
import com.example.network.GeminiRepository

object DefaultAppContainer {
    private var database: AppDatabase? = null
    val geminiRepository: GeminiRepository by lazy { GeminiRepository() }

    fun getRepository(context: Context): RecordingRepository {
        if (database == null) {
            database = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "call_scribe_database"
            )
            .fallbackToDestructiveMigration()
            .build()
        }
        return RecordingRepository(database!!.recordingDao())
    }
}
