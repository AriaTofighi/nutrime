package com.nutrime.app.model

import kotlinx.serialization.Serializable

@Serializable
data class Serving(val label: String, val grams: Double)

enum class FoodSource(val label: String) {
    USDA("USDA"),
    /** NutriMe's own curated foods (data/foods), bundled with the USDA database. */
    NUTRIME("NutriMe"),
    USDA_BRANDED("USDA Branded"),
    OPEN_FOOD_FACTS("Open Food Facts"),
    CUSTOM("Custom"),
    RECIPE("Recipe"),
}

@Serializable
data class Food(
    /** "usda:<fdcId>", "nm:<key>" (curated), "off:<barcode>", or "custom:<uuid>". */
    val id: String,
    val source: FoodSource,
    val name: String,
    val brand: String? = null,
    /** Per 100 g. */
    val nutrients: NutrientMap,
    val servings: List<Serving> = emptyList(),
    val barcode: String? = null,
    val category: String? = null,
    /** Where a branded product is sold, as Open Food Facts country names ("canada", "united-states"). Empty when unknown. */
    val countries: List<String> = emptyList(),
) {
    /** All choosable servings: the food's own portions plus gram/ounce units. */
    val allServings: List<Serving>
        get() = buildList {
            addAll(servings)
            if (none { it.label == "g" }) add(Serving("g", 1.0))
            if (none { it.label == "oz" }) add(Serving("oz", 28.3495))
            if (none { it.label == "100 g" }) add(Serving("100 g", 100.0))
        }

    val defaultServing: Serving get() = servings.firstOrNull() ?: Serving("g", 1.0)
    val defaultQuantity: Double get() = if (servings.isEmpty()) 100.0 else 1.0

    val kcalPer100g: Double get() = nutrients[N.ENERGY] ?: 0.0
}

enum class Meal(val label: String) {
    BREAKFAST("Breakfast"), LUNCH("Lunch"), DINNER("Dinner"), SNACKS("Snacks");
}
