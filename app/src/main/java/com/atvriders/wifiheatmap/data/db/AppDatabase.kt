package com.atvriders.wifiheatmap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SurveyEntity::class, FloorPlanEntity::class, SampleEntity::class, ReadingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
    abstract fun sampleDao(): SampleDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "wifiheatmap.db")
                .build()
    }
}
