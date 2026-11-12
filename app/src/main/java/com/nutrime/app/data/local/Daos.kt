package com.nutrime.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE date = :date AND deleted = 0 ORDER BY createdAt")
    fun observeDay(date: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE date BETWEEN :from AND :to AND deleted = 0")
    fun observeRange(from: String, to: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun get(id: String): DiaryEntryEntity?

    @Query("SELECT * FROM diary_entries WHERE date = :date AND deleted = 0")
    suspend fun getDay(date: String): List<DiaryEntryEntity>

    @Query(
        """SELECT * FROM diary_entries WHERE id IN (
             SELECT id FROM diary_entries d WHERE deleted = 0 AND createdAt = (
               SELECT MAX(createdAt) FROM diary_entries d2 WHERE d2.foodId = d.foodId AND d2.deleted = 0))
           ORDER BY createdAt DESC LIMIT :limit"""
    )
    fun observeRecent(limit: Int = 40): Flow<List<DiaryEntryEntity>>

    /** How often each food was logged since [since], at which meals, and when last. */
    @Query(
        """SELECT foodId, COUNT(*) AS cnt, MAX(createdAt) AS lastAt,
             SUM(CASE WHEN meal = 0 THEN 1 ELSE 0 END) AS m0, SUM(CASE WHEN meal = 1 THEN 1 ELSE 0 END) AS m1,
             SUM(CASE WHEN meal = 2 THEN 1 ELSE 0 END) AS m2, SUM(CASE WHEN meal = 3 THEN 1 ELSE 0 END) AS m3
           FROM diary_entries
           WHERE deleted = 0 AND date >= :since AND foodId NOT LIKE 'quick:%' AND foodId NOT LIKE 'import:%'
           GROUP BY foodId"""
    )
    fun observeUsage(since: String): Flow<List<FoodUsageRow>>

    /** The newest entry of a food (its usual amount). */
    @Query("SELECT * FROM diary_entries WHERE foodId = :foodId AND deleted = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastOf(foodId: String): DiaryEntryEntity?

    /** Entries changed (including deleted ones) since [since], for writing to Health Connect. */
    @Query("SELECT * FROM diary_entries WHERE updatedAt >= :since")
    suspend fun changedSince(since: Long): List<DiaryEntryEntity>

    @Query("SELECT MIN(date) FROM diary_entries WHERE deleted = 0")
    fun observeFirstDate(): Flow<String?>

    @Query("SELECT * FROM diary_entries WHERE deleted = 0 ORDER BY date, createdAt")
    suspend fun all(): List<DiaryEntryEntity>

    @Upsert
    suspend fun upsert(entry: DiaryEntryEntity)

    @Upsert
    suspend fun upsertAll(entries: List<DiaryEntryEntity>)

    @Query("SELECT * FROM diary_entries WHERE dirty = 1")
    suspend fun dirty(): List<DiaryEntryEntity>

    @Query("UPDATE diary_entries SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE diary_entries SET dirty = 1")
    suspend fun markAllDirty()
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun get(id: String): FoodEntity?

    @Query("SELECT * FROM foods WHERE barcode = :barcode AND deleted = 0 LIMIT 1")
    suspend fun byBarcode(barcode: String): FoodEntity?

    @Query("SELECT * FROM foods WHERE deleted = 0")
    suspend fun all(): List<FoodEntity>

    @Query("SELECT * FROM foods WHERE deleted = 0 AND kind IN ('custom','recipe') ORDER BY name COLLATE NOCASE")
    fun observeMine(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE deleted = 0 AND kind NOT IN ('custom','recipe') ORDER BY name COLLATE NOCASE")
    fun observeSaved(): Flow<List<FoodEntity>>

    @Query(
        """SELECT * FROM foods WHERE deleted = 0 AND
           (name LIKE '%' || :q || '%' OR brand LIKE '%' || :q || '%')
           ORDER BY (kind IN ('custom','recipe')) DESC, LENGTH(name) LIMIT 30"""
    )
    suspend fun search(q: String): List<FoodEntity>

    @Upsert
    suspend fun upsert(food: FoodEntity)

    @Upsert
    suspend fun upsertAll(foods: List<FoodEntity>)

    @Query("SELECT * FROM foods WHERE dirty = 1")
    suspend fun dirty(): List<FoodEntity>

    @Query("UPDATE foods SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE foods SET dirty = 1")
    suspend fun markAllDirty()
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights WHERE deleted = 0 ORDER BY date DESC")
    fun observeAll(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights WHERE date = :date AND deleted = 0")
    suspend fun onDate(date: String): List<WeightEntity>

    @Query("SELECT * FROM weights WHERE deleted = 0 ORDER BY date DESC LIMIT 1")
    suspend fun latest(): WeightEntity?

    @Query("SELECT * FROM weights WHERE id = :id")
    suspend fun get(id: String): WeightEntity?

    @Upsert
    suspend fun upsert(entry: WeightEntity)

    @Upsert
    suspend fun upsertAll(entries: List<WeightEntity>)

    @Query("SELECT * FROM weights WHERE dirty = 1")
    suspend fun dirty(): List<WeightEntity>

    @Query("UPDATE weights SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE weights SET dirty = 1")
    suspend fun markAllDirty()
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 'profile'")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 'profile'")
    suspend fun get(): ProfileEntity?

    @Upsert
    suspend fun upsert(p: ProfileEntity)

    @Query("SELECT * FROM profile WHERE dirty = 1")
    suspend fun dirty(): List<ProfileEntity>

    @Query("UPDATE profile SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE profile SET dirty = 1")
    suspend fun markAllDirty()
}

data class FoodUsageRow(val foodId: String, val cnt: Int, val lastAt: Long, val m0: Int, val m1: Int, val m2: Int, val m3: Int)

@Dao
interface DayDao {
    @Query("SELECT * FROM days WHERE deleted = 0")
    fun observeAll(): Flow<List<DayEntity>>

    @Query("SELECT * FROM days WHERE id = :id AND deleted = 0")
    fun observe(id: String): Flow<DayEntity?>

    @Query("SELECT * FROM days WHERE id = :id")
    suspend fun get(id: String): DayEntity?

    @Query("SELECT * FROM days WHERE deleted = 0")
    suspend fun all(): List<DayEntity>

    @Upsert
    suspend fun upsert(day: DayEntity)

    @Query("SELECT * FROM days WHERE dirty = 1")
    suspend fun dirty(): List<DayEntity>

    @Query("UPDATE days SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE days SET dirty = 1")
    suspend fun markAllDirty()
}

@Dao
interface SavedMealDao {
    @Query("SELECT * FROM meals WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SavedMealEntity>>

    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun get(id: String): SavedMealEntity?

    @Query("SELECT * FROM meals WHERE deleted = 0")
    suspend fun all(): List<SavedMealEntity>

    @Upsert
    suspend fun upsert(meal: SavedMealEntity)

    @Query("SELECT * FROM meals WHERE dirty = 1")
    suspend fun dirty(): List<SavedMealEntity>

    @Query("UPDATE meals SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE meals SET dirty = 1")
    suspend fun markAllDirty()
}
