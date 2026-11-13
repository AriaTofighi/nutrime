package com.nutrime.server.food

import com.nutrime.app.model.FoodNames

/**
 * Remove repeated parts from a product name. Crowd-sourced names often repeat the brand
 * ("Greek Yogurt, Plain - Kirkland Signature - Kirkland Signature"). See [FoodNames.display] for the full cleaner.
 */
fun cleanFoodName(name: String, brand: String?): String = FoodNames.display(name, brand)

/** An ISO country code ("CA") as the country name Open Food Facts uses in its tags ("canada"), or null. */
fun countryName(code: String?): String? = FoodNames.countryName(code)

/** Products sold in [country] first; the order is otherwise kept. */
fun <T> List<T>.preferCountry(country: String?, countries: (T) -> List<String>): List<T> =
    if (country == null) this else sortedBy { if (country in countries(it)) 0 else 1 }

/** The name to show: cleaned, with the brand in brackets unless the name already contains it. */
fun displayFoodName(name: String, brand: String?): String {
    val clean = cleanFoodName(name, brand)
    val b = brand?.trim()?.takeIf { it.isNotEmpty() } ?: return clean
    return if (clean.lowercase().contains(b.lowercase())) clean else "$clean ($b)"
}
