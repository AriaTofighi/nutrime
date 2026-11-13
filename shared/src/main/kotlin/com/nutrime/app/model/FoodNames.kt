package com.nutrime.app.model

import java.util.Locale

/**
 * Food names for display, and the country form used for ranking. Copied in web/src/lib/foodNames.ts;
 * change both together. Names are cleaned only for display (and when the server parses provider
 * results): stored diary entries and ids never change.
 */
object FoodNames {
    private val NOT_LETTER = Regex("[^\\p{L}]")

    private fun letters(s: String) = s.replace(NOT_LETTER, "")

    /** Letters only upper case, at least two of them ("CRACKER BARREL", "APPLEBEE'S"). */
    fun isShouted(s: String): Boolean {
        val l = letters(s)
        return l.length >= 2 && l == l.uppercase(Locale.ROOT) && l != l.lowercase(Locale.ROOT)
    }

    private fun isLower(s: String): Boolean {
        val l = letters(s)
        return l.length >= 2 && l == l.lowercase(Locale.ROOT) && l != l.uppercase(Locale.ROOT)
    }

    private val WORD_START = Regex("(^|[\\s\\-/&(])(\\p{L})")

    /** "CRACKER BARREL" → "Cracker Barrel", "APPLEBEE'S" → "Applebee's", "CHICK-FIL-A" → "Chick-Fil-A". */
    fun titleCase(s: String): String =
        WORD_START.replace(s.lowercase(Locale.ROOT)) { m -> m.groupValues[1] + m.groupValues[2].uppercase(Locale.ROOT) }

    /** "CHICKEN TENDERLOINS" or "chicken tenderloins" → "Chicken tenderloins". */
    fun sentenceCase(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        val i = lower.indexOfFirst { it.isLetter() }
        return if (i < 0) lower else lower.substring(0, i) + lower[i].uppercase(Locale.ROOT) + lower.substring(i + 1)
    }

    /** Remove parts repeated with " - " ("Greek Yogurt - Kirkland Signature - Kirkland Signature"), and a part equal to the brand. */
    fun clean(name: String, brand: String?): String {
        val b = brand?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val parts = name.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return name.trim()
        val seen = mutableSetOf<String>()
        val kept = parts.filter { p -> val k = p.lowercase(Locale.ROOT); k != b && seen.add(k) }
        return kept.joinToString(" - ").ifEmpty { parts.first() }
    }

    private val SPACES = Regex("\\s+")
    private val LEAD_SEPARATOR = Regex("^[\\s,:\\-–]+")

    /**
     * The name to show: trimmed, single spaces, repeated parts and a leading brand removed (the brand is
     * shown on its own), all-caps or all-lowercase names in sentence case, and a SHOUTED leading brand
     * segment ("CRACKER BARREL, chicken tenderloin platter") in title case.
     */
    fun display(name: String, brand: String?): String {
        var s = clean(name.replace(SPACES, " ").trim(), brand)
        val b = brand?.replace(SPACES, " ")?.trim()
        if (!b.isNullOrEmpty() && s.length > b.length && s.lowercase(Locale.ROOT).startsWith(b.lowercase(Locale.ROOT))) {
            val after = s.substring(b.length)
            val rest = after.replace(LEAD_SEPARATOR, "")
            if (LEAD_SEPARATOR.containsMatchIn(after) && letters(rest).length >= 3) s = rest
        }
        if (isShouted(s) || isLower(s)) return sentenceCase(s)
        val comma = s.indexOf(',')
        if (comma > 0 && isShouted(s.substring(0, comma)) && !isShouted(s.substring(comma + 1))) {
            return titleCase(s.substring(0, comma)) + s.substring(comma)
        }
        return if (s.isEmpty()) s else s.substring(0, 1).uppercase(Locale.ROOT) + s.substring(1)
    }

    fun display(food: Food): String = display(food.name, food.brand)

    /** An ISO country code ("CA") as the country name Open Food Facts uses in its tags ("canada"), or null. */
    fun countryName(code: String?): String? {
        val c = code?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: return null
        val name = Locale("", c).getDisplayCountry(Locale.ENGLISH)
        if (name.isBlank() || name.equals(c, ignoreCase = true)) return null
        return name.lowercase(Locale.ROOT).replace(Regex("[^a-z]+"), "-").trim('-').ifEmpty { null }
    }
}
