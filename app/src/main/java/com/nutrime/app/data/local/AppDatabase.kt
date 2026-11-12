package com.nutrime.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DiaryEntryEntity::class, FoodEntity::class, WeightEntity::class, ProfileEntity::class,
        DayEntity::class, SavedMealEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // Version 2 only adds the days and meals tables; existing tables are not touched.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diary(): DiaryDao
    abstract fun foods(): FoodDao
    abstract fun weights(): WeightDao
    abstract fun profile(): ProfileDao
    abstract fun days(): DayDao
    abstract fun meals(): SavedMealDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nutrime.db").build()
    }
}
