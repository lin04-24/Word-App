package com.example.moci.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WordEntity::class, ReviewLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class VocabularyDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao

    companion object {
        @Volatile
        private var instance: VocabularyDatabase? = null

        fun getInstance(context: Context): VocabularyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VocabularyDatabase::class.java,
                "moci.db",
            ).build().also { instance = it }
        }
    }
}
