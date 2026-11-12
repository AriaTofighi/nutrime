package com.nutrime.server.food

/**
 * Remove repeated parts from a product name. Crowd-sourced names often repeat the brand
 * ("Greek Yogurt, Plain - Kirkland Signature - Kirkland Signature").
 */
fun cleanFoodName(name: String, brand: String?): String {
    val b = brand?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val parts = name.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 2) return name.trim()
    val seen = mutableSetOf<String>()
    val kept = parts.filter { p -> val k = p.lowercase(); k != b && seen.add(k) }
    return kept.joinToString(" - ").ifEmpty { parts.first() }
}

/** An ISO country code ("CA") as the country name Open Food Facts uses in its tags ("canada"), or null. */
fun countryName(code: String?): String? {
    val c = code?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: return null
    val name = java.util.Locale("", c).getDisplayCountry(java.util.Locale.ENGLISH)
    if (name.isBlank() || name.equals(c, ignoreCase = true)) return null
    return name.lowercase().replace(Regex("[^a-z]+"), "-").trim('-')
}

/** Products sold in [country] first; the order is otherwise kept. */
fun <T> List<T>.preferCountry(country: String?, countries: (T) -> List<String>): List<T> =
    if (country == null) this else sortedBy { if (country in countries(it)) 0 else 1 }

/** The name to show: cleaned, with the brand in brackets unless the name already contains it. */
fun displayFoodName(name: String, brand: String?): String {
    val clean = cleanFoodName(name, brand)
    val b = brand?.trim()?.takeIf { it.isNotEmpty() } ?: return clean
    return if (clean.lowercase().contains(b.lowercase())) clean else "$clean ($b)"
}
