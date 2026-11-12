package com.nutrime.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Every synced entity has a string id, an updatedAt timestamp (ms, last-writer-wins),
 * a soft-delete flag, and a local-only dirty flag marking unsynced changes.
 */

@Serializable
@Entity(tableName = "diary_entries", indices = [Index("date"), Index("foodId")])
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    /** ISO date yyyy-MM-dd. */
    val date: String,
    val meal: Int,
    val foodId: String,
    val foodName: String,
    val brand: String? = null,
    val servingLabel: String,
    val servingGrams: Double,
    val quantity: Double,
    /** Snapshot of the food's nutrients per 100 g at logging time (JSON map). */
    val nutrientsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    @Transient val dirty: Boolean = true,
) {
    val grams: Double get() = servingGrams * quantity
}

@Serializable
@Entity(tableName = "foods", indices = [Index("barcode")])
data class FoodEntity(
    /** "custom:<uuid>" for user foods/recipes, or the remote id for saved online foods. */
    @PrimaryKey val id: String,
    /** "custom", "recipe", "usda_branded", "usda", "off". */
    val kind: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    /** Per 100 g (JSON map). */
    val nutrientsJson: String,
    /** JSON list of Serving. */
    val servingsJson: String = "[]",
    /** Recipes only: JSON list of RecipeIngredient. */
    val ingredientsJson: String? = null,
    val recipeServings: Double? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
    @Transient val dirty: Boolean = true,
)

@Serializable
@Entity(tableName = "weights", indices = [Index("date")])
data class WeightEntity(
    @PrimaryKey val id: String,
    val date: String,
    val weightKg: Double,
    val bodyFatPct: Double? = null,
    val note: String? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
    @Transient val dirty: Boolean = true,
)

@Serializable
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: String = "profile",
    val json: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    @Transient val dirty: Boolean = true,
)

@Serializable
data class RecipeIngredient(
    val foodId: String,
    val name: String,
    val servingLabel: String,
    val servingGrams: Double,
    val quantity: Double,
    /** Per 100 g snapshot. */
    val nutrients: Map<Int, Double>,
) {
    val grams: Double get() = servingGrams * quantity
}

/** One record per calendar date (id = ISO date): "day complete" flag, note, and activity from Health Connect. */
@Serializable
@Entity(tableName = "days")
data class DayEntity(
    @PrimaryKey val id: String,
    val complete: Boolean = false,
    val note: String? = null,
    val steps: Int? = null,
    val activeKcal: Double? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
    @Transient val dirty: Boolean = true,
)

/** A named list of foods. Logging it makes one diary entry per item (a recipe makes one entry). */
@Serializable
@Entity(tableName = "meals")
data class SavedMealEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** JSON list of RecipeIngredient. */
    val itemsJson: String,
    /** The meal it was saved from (0..3). */
    val defaultMeal: Int? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
    @Transient val dirty: Boolean = true,
)
