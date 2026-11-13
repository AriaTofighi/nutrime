package com.nutrime.server

import com.nutrime.app.model.ActivityLevel
import com.nutrime.app.model.AdaptiveBurn
import com.nutrime.app.model.BucketSize
import com.nutrime.app.model.BurnMode
import com.nutrime.app.model.DayLog
import com.nutrime.app.model.Dri
import com.nutrime.app.model.Export
import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.FoodUsage
import com.nutrime.app.model.Fuzzy
import com.nutrime.app.model.Goal
import com.nutrime.app.model.GoalWarning
import com.nutrime.app.model.Insights
import com.nutrime.app.model.LoggedItem
import com.nutrime.app.model.N
import com.nutrime.app.model.Profile
import com.nutrime.app.model.ProteinMode
import com.nutrime.app.model.QueryAmount
import com.nutrime.app.model.SearchRank
import com.nutrime.app.model.Serving
import com.nutrime.app.model.Sex
import com.nutrime.app.model.TrendBuckets
import com.nutrime.app.model.TrendRange
import com.nutrime.app.model.WeighIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logic in shared/ that the app, the web app (as a TypeScript copy) and the assistant use. */
class SharedLogicTest {
    @Serializable private data class VProfile(val sex: String = "MALE", val heightCm: Double = 175.0, val weightKg: Double = 75.0, val activity: String = "LIGHT")
    @Serializable private data class VLog(val date: String, val kcal: Double, val carbsG: Double? = null, val complete: Boolean? = null, val hasEntries: Boolean? = null)
    @Serializable private data class VWeight(val date: String, val kg: Double)
    @Serializable private data class VTruth(val burnKcal: Int, val tolerance: Int)
    @Serializable private data class VExpect(
        val burnKcal: Int, val burnSd: Int, val confidence: String, val trendKg: Double, val ratePerWeekKg: Double,
        val knownDays: Int, val weighIns: Int, val spanDays: Int,
    )
    @Serializable private data class Vector(
        val name: String, val age: Int, val profile: VProfile, val today: String,
        val logs: List<VLog>, val weights: List<VWeight>, val truth: VTruth? = null, val expect: VExpect?,
    )
    @Serializable private data class Vectors(val vectors: List<Vector>)

    private fun vectors(): List<Vector> {
        val file = listOf("../shared/testdata/burn_vectors.json", "shared/testdata/burn_vectors.json").map(::File).first { it.exists() }
        return Json { ignoreUnknownKeys = true }.decodeFromString(Vectors.serializer(), file.readText()).vectors
    }

    /** Kotlin and TypeScript (web/scripts/burn_vectors.ts) must learn the same burn from the same data. */
    @Test
    fun burnFilterMatchesTheSharedVectors() {
        val year = LocalDate.now().year
        vectors().forEach { v ->
            val p = Profile(
                sex = Sex.valueOf(v.profile.sex), birthYear = year - v.age, heightCm = v.profile.heightCm,
                weightKg = v.profile.weightKg, activity = ActivityLevel.valueOf(v.profile.activity),
            )
            val logs = v.logs.map { l -> DayLog(LocalDate.parse(l.date), l.kcal, l.carbsG ?: 0.0, l.complete ?: false, l.hasEntries ?: (l.kcal > 0)) }
            val weights = v.weights.map { WeighIn(LocalDate.parse(it.date), it.kg) }
            val e = AdaptiveBurn.estimate(p, logs, weights, LocalDate.parse(v.today))
            val want = v.expect
            if (want == null) { assertNull(e, v.name); return@forEach }
            assertNotNull(e, v.name)
            assertTrue(abs(e.burnKcal - want.burnKcal) <= 1, "${v.name}: burn ${e.burnKcal}, expected ${want.burnKcal}")
            assertEquals(want.confidence, e.confidence.name, v.name)
            assertTrue(abs(e.trendKg - want.trendKg) < 1e-3, "${v.name}: trend ${e.trendKg}, expected ${want.trendKg}")
            assertEquals(want.knownDays, e.knownDays, v.name)
            assertEquals(want.weighIns, e.weighIns, v.name)
            assertEquals(want.spanDays, e.spanDays, v.name)
            v.truth?.let { t -> assertTrue(abs(e.burnKcal - t.burnKcal) <= t.tolerance, "${v.name}: ${e.burnKcal} vs true ${t.burnKcal}") }
        }
    }

    @Test
    fun noDataKeepsTheFormulaTarget() {
        val p = Profile(weightKg = 80.0, burnMode = BurnMode.ADAPTIVE)
        assertEquals(p.formulaTdee, p.tdee)
        assertNull(AdaptiveBurn.estimate(p, emptyList(), emptyList(), LocalDate.now()))
    }

    @Test
    fun partlyLoggedDaysAreNotTrusted() {
        val today = LocalDate.of(2026, 6, 30)
        val y = today.minusDays(1)
        assertTrue(AdaptiveBurn.isKnown(DayLog(y, 1500.0), 2000.0, today))
        assertTrue(!AdaptiveBurn.isKnown(DayLog(y, 600.0), 2000.0, today))
        assertTrue(AdaptiveBurn.isKnown(DayLog(y, 0.0, complete = true), 2000.0, today), "a complete fasting day counts")
        assertTrue(!AdaptiveBurn.isKnown(DayLog(today, 2500.0), 2000.0, today), "today is still going")
    }

    @Test
    fun checkInStepIsLimited() {
        assertEquals(2150 to true, AdaptiveBurn.nextAccepted(2000.0, 2400))
        assertEquals(2100 to false, AdaptiveBurn.nextAccepted(2000.0, 2100))
        assertEquals(1850 to true, AdaptiveBurn.nextAccepted(2000.0, 1500))
    }

    @Test
    fun burnModeDrivesTheTarget() {
        val base = Profile(weightKg = 80.0, goalKgPerWeek = -0.5)
        val learned = base.copy(burnMode = BurnMode.ADAPTIVE, adaptiveBurnKcal = 2600)
        assertEquals(2600 - 550, learned.computedCalories)
        assertEquals(2400 - 550, base.copy(burnMode = BurnMode.MANUAL, manualBurnKcal = 2400).computedCalories)
        assertTrue(base.copy(burnMode = BurnMode.MANUAL, manualBurnKcal = 1000).raisedToFloor)
    }

    @Test
    fun goalProjectionAndWarnings() {
        val today = LocalDate.of(2026, 1, 1)
        assertEquals(today.plusDays(140), Goal.projectedDate(today, 80.0, 70.0, -0.5))
        assertNull(Goal.projectedDate(today, 80.0, 70.0, 0.25), "wrong direction")
        assertEquals(0.0, Goal.weeksToGoal(70.1, 70.0, -0.5))
        val p = Profile(weightKg = 60.0, goalKgPerWeek = -1.0, goalWeightKg = 65.0)
        val w = Goal.warnings(p)
        assertTrue(GoalWarning.FAST_RATE in w)
        assertTrue(GoalWarning.WRONG_DIRECTION in w)
    }

    @Test
    fun proteinPerKgAndCalorieSchedule() {
        val p = Profile(weightKg = 80.0, calorieOverride = 2000, proteinMode = ProteinMode.PER_KG, proteinGPerKg = 2.0, fatPct = 30)
        val g = Dri.macroGrams(p)
        assertEquals(160.0, g.protein, 1e-9)
        assertEquals(2000 * 0.3 / 9, g.fat, 1e-9)
        assertEquals((2000 - 640 - 600) / 4.0, g.carbs, 1e-9)
        val s = Goal.higherWeekends(250)
        assertEquals(0, s.sum())
        assertEquals(250, s[5]); assertEquals(250, s[6])
        val edited = Goal.editSchedule(s, 0, 300, setOf(0))
        assertEquals(0, edited.sum())
        assertEquals(300, edited[0])
        val sched = p.copy(calorieSchedule = s)
        assertEquals(2250, sched.calorieTargetOn(LocalDate.of(2026, 11, 14))) // a Saturday
        assertEquals(2250.0, Dri.targets(sched, LocalDate.of(2026, 11, 14))[N.ENERGY]!!.min!!, 1e-9)
    }

    @Test
    fun rangesAndBuckets() {
        assertEquals(TrendRange.Last(30), TrendRange.decode("30d"))
        assertEquals(TrendRange.All, TrendRange.decode("all"))
        val c = TrendRange.decode("2026-01-10..2026-01-01")
        assertEquals(TrendRange.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10)), c)
        assertNull(TrendRange.custom(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)), "over 730 days")
        assertEquals(BucketSize.DAY, TrendBuckets.buckets(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).first().size)
        val weeks = TrendBuckets.buckets(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 28))
        assertEquals(BucketSize.WEEK, weeks.first().size)
        assertEquals(LocalDate.of(2026, 1, 4), weeks.first().to, "weeks end on Sunday, across the year end")
        val months = TrendBuckets.buckets(LocalDate.of(2025, 6, 15), LocalDate.of(2026, 6, 14))
        assertEquals(BucketSize.MONTH, months.first().size)
        assertEquals(13, months.size)
        assertEquals(LocalDate.of(2025, 12, 1) to LocalDate.of(2025, 12, 31), TrendBuckets.previous(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
        val avg = TrendBuckets.average(months.take(1), mapOf(LocalDate.of(2025, 6, 20) to 2000.0, LocalDate.of(2025, 6, 21) to 1000.0),
            setOf(LocalDate.of(2025, 6, 20))) { it.average() }
        assertEquals(listOf<Double?>(2000.0), avg, "only counted days")
    }

    private fun food(id: String, name: String, source: FoodSource = FoodSource.USDA, micros: Int = 20) =
        Food(id, source, name, nutrients = (mapOf(N.ENERGY to 100.0) + (0 until micros).associate { 3000 + it to 1.0 }))

    @Test
    fun searchRanksFoodsTheUserLogs() {
        val a = food("usda:1", "Yogurt, plain, whole milk")
        val b = food("usda:2", "Yogurt, Greek, plain, nonfat")
        val usage = mapOf("usda:2" to FoodUsage("usda:2", 12, 2.0, listOf(10, 0, 0, 2)))
        assertEquals(listOf(b, a), SearchRank.rank(listOf(a, b), "yogurt", usage, meal = 0, favorites = emptySet()))
        // A favourite moves up too; a corrected match moves down.
        assertEquals(a, SearchRank.rank(listOf(b, a), "yogurt", emptyMap(), null, setOf("usda:1")).first())
        assertEquals(a, SearchRank.rank(listOf(b, a), "yogurt", emptyMap(), null, emptySet(), corrected = setOf("usda:2")).first())
    }

    @Serializable private data class RFood(
        val id: String, val source: String, val name: String, val brand: String? = null, val category: String? = null,
        val kcal: Double, val micros: Int, val servings: List<List<kotlinx.serialization.json.JsonPrimitive>> = emptyList(),
        val countries: List<String> = emptyList(),
    )
    @Serializable private data class RCase(
        val query: String, val country: String? = null, val usage: Map<String, Int> = emptyMap(), val favorites: List<String> = emptyList(),
        val first: String? = null, val before: List<List<String>> = emptyList(), val hidden: List<String> = emptyList(),
        val shown: List<String> = emptyList(), val moreResults: List<String> = emptyList(),
    )
    @Serializable private data class RVectors(val foods: List<RFood>, val cases: List<RCase>)

    /** Kotlin and TypeScript (web/scripts/search_rank_vectors.ts) must rank, collapse and group the same way. */
    @Test
    fun searchRankMatchesTheSharedCases() {
        val file = listOf("../shared/testdata/search_rank.json", "shared/testdata/search_rank.json").map(::File).first { it.exists() }
        val data = Json { ignoreUnknownKeys = true }.decodeFromString(RVectors.serializer(), file.readText())
        // Vitamin and mineral ids (not label nutrients), in the same order as the TypeScript script.
        val micros = listOf(1051, 1057, 1018, 1292, 1293, 1404, 1278, 1272, 1316, 1106, 1162, 1114, 1109, 1185, 1165, 1166, 1167, 1170, 1175, 1190, 1178, 1180, 1087, 1098, 1089, 1090, 1101, 1091, 1092, 1103)
        val foods = data.foods.map { v ->
            Food(
                v.id, FoodSource.valueOf(v.source), v.name, v.brand,
                nutrients = mapOf(N.ENERGY to v.kcal) + micros.take(v.micros).associateWith { 1.0 },
                servings = v.servings.map { Serving(it[0].content, it[1].content.toDouble()) },
                category = v.category, countries = v.countries,
            )
        }
        data.cases.forEach { c ->
            val q = com.nutrime.app.model.FoodQuality.words(c.query)
            val usage = c.usage.mapValues { (id, n) -> FoodUsage(id, n, 1.0, listOf(n, 0, 0, 0)) }
            val favorites = c.favorites.toSet()
            val candidates = foods.filter { com.nutrime.app.model.FoodQuality.matchedWords(q, it) > 0 }
            val ranked = SearchRank.collapse(SearchRank.rank(candidates, c.query, usage, null, favorites, c.country), usage.keys, favorites, c.country)
            val (main, more) = SearchRank.splitMoreResults(ranked, usage.keys, favorites)
            val ids = ranked.map { it.id }
            val label = c.query + (if (c.usage.isNotEmpty()) " (with usage)" else "") + (if (c.favorites.isNotEmpty()) " (with favourite)" else "")
            c.first?.let { assertEquals(it, main.firstOrNull()?.id, "$label: first of ${main.map { f -> f.id }}") }
            c.before.forEach { (a, b) ->
                val i = ids.indexOf(a)
                val j = ids.indexOf(b)
                assertTrue(i >= 0 && j >= 0 && i < j, "$label: $a (at $i) must come before $b (at $j) in $ids")
            }
            c.hidden.forEach { assertTrue(it !in ids, "$label: $it must be merged into another row") }
            c.shown.forEach { assertTrue(it in ids, "$label: $it must be shown") }
            c.moreResults.forEach { m -> assertTrue(more.any { it.id == m }, "$label: $m must be under More results") }
        }
    }

    @Test
    fun foodNamesForDisplay() {
        val names = com.nutrime.app.model.FoodNames
        assertEquals("Cracker Barrel, chicken tenderloin platter, fried", names.display("CRACKER BARREL, chicken tenderloin platter, fried", null))
        assertEquals("Chicken tenderloins", names.display("CHICKEN TENDERLOINS", "Great Value"))
        assertEquals("Chicken tenderloin", names.display("chicken  tenderloin ", null))
        assertEquals("Greek Yogurt, Plain", names.display("Greek Yogurt, Plain - Kirkland Signature - Kirkland Signature", "Kirkland Signature"))
        assertEquals("Greek Yogurt", names.display("Kirkland Signature Greek Yogurt", "Kirkland Signature"))
        assertEquals("Applebee's, chicken tenders", names.display("APPLEBEE'S, chicken tenders", null))
        assertEquals("Bananas, raw", names.display("Bananas, raw", null))
        assertEquals("canada", names.countryName("CA"))
        assertEquals("united-states", names.countryName("us"))
        assertNull(names.countryName("XX1"))
    }

    @Test
    fun typingCorrection() {
        assertEquals(1, Fuzzy.distance("chiken", "chicken"))
        assertEquals(1, Fuzzy.distance("brocoli", "broccoli"))
        val vocab = listOf("chicken" to 500, "chickpeas" to 40, "cheese" to 300)
        assertEquals(listOf("chicken"), Fuzzy.corrections("chiken", vocab))
        assertTrue(Fuzzy.corrections("egg", vocab).isEmpty(), "short words are not corrected")
    }

    @Test
    fun amountsInTheQuery() {
        assertEquals(QueryAmount("chicken", 200.0, "g"), QueryAmount.parse("200g chicken"))
        assertEquals(QueryAmount("chicken breast", 150.0, "g"), QueryAmount.parse("chicken breast 150 g"))
        assertEquals(QueryAmount("eggs", 2.0, null), QueryAmount.parse("2 eggs"))
        assertEquals(QueryAmount("rice", 1.5, "cup"), QueryAmount.parse("1.5 cups rice"))
        assertEquals(QueryAmount("avocado", 0.5, null), QueryAmount.parse("½ avocado"))
        assertEquals(QueryAmount("banana", 3.0, null), QueryAmount.parse("3 x banana"))
        assertNull(QueryAmount.parse("milk 2%"))
        assertNull(QueryAmount.parse("coke zero 330"))
        val egg = Food("usda:3", FoodSource.USDA, "Egg", nutrients = emptyMap(), servings = listOf(Serving("1 large", 50.0)))
        assertEquals(Serving("1 large", 50.0) to 2.0, QueryAmount.parse("2 eggs")!!.servingFor(egg))
        assertEquals(Serving("g", 1.0) to 200.0, QueryAmount.parse("200g egg")!!.servingFor(egg))
        assertEquals(Serving("g", 1.0) to 1000.0, QueryAmount("x", 1.0, "kg").servingFor(egg))
    }

    @Test
    fun topSourcesAndPatterns() {
        val d = LocalDate.of(2026, 3, 2) // Monday
        val items = listOf(
            LoggedItem(d, 0, "usda:bread", "Bread", mapOf(N.SODIUM to 400.0, N.ENERGY to 300.0)),
            LoggedItem(d.plusDays(1), 0, "usda:bread", "Bread", mapOf(N.SODIUM to 400.0, N.ENERGY to 300.0)),
            LoggedItem(d, 2, "quick:a", "Pizza", mapOf(N.SODIUM to 1200.0, N.ENERGY to 900.0)),
            LoggedItem(d.plusDays(5), 2, "quick:b", "pizza", mapOf(N.SODIUM to 1000.0, N.ENERGY to 900.0)),
        )
        val s = Insights.topSources(items, N.SODIUM)
        assertEquals(listOf("pizza", "Bread").map { it.lowercase() }, s.map { it.name.lowercase() })
        assertEquals(2200.0 / 3000, s.first().share, 1e-9)
        val pat = Insights.patterns(items, mapOf(d to 1200.0, d.plusDays(1) to 300.0, d.plusDays(5) to 900.0), d, d.plusDays(6))
        assertEquals(750.0, pat.weekdayKcal!!, 1e-9)
        assertEquals(900.0, pat.weekendKcal!!, 1e-9)
        assertEquals(3, pat.loggedDays)
        assertEquals(1, pat.currentRun)
    }

    @Test
    fun csvEscaping() {
        assertEquals("\"a, b\"", Export.cell("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", Export.cell("say \"hi\""))
        assertEquals("\"two\nlines\"", Export.cell("two\nlines"))
        assertEquals("1.5", Export.cell(1.5))
        assertEquals("", Export.cell(null))
        val csv = Export.weightsCsv(listOf(Export.WeightRow("2026-01-02", 80.0, null, 79.876, "after, run")))
        assertEquals("Date,Weight (kg),Body fat (%),Trend weight (kg),Note\r\n2026-01-02,80,,79.88,\"after, run\"\r\n", csv)
        val zip = Export.zip(mapOf("a.txt" to "hello"))
        assertTrue(zip.size > 20 && zip[0] == 'P'.code.toByte() && zip[1] == 'K'.code.toByte())
    }
}
