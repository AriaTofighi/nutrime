package com.nutrime.app.data

import com.nutrime.app.AppContainer
import com.nutrime.app.data.local.DayEntity
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.data.local.FoodEntity
import com.nutrime.app.data.local.ProfileEntity
import com.nutrime.app.data.local.SavedMealEntity
import com.nutrime.app.data.local.WeightEntity
import com.nutrime.app.data.remote.Http
import com.nutrime.app.model.Export
import com.nutrime.app.model.sumNutrients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Every record, in the sync shapes: the web app can restore it with "Import data". */
@Serializable
data class ExportRecords(
    val diary: List<DiaryEntryEntity>,
    val foods: List<FoodEntity>,
    val weights: List<WeightEntity>,
    val profile: List<ProfileEntity>,
    val days: List<DayEntity>,
    val meals: List<SavedMealEntity>,
)

/** "Export your data": a ZIP of CSV files for spreadsheets, and nutrime.json with every record. */
suspend fun exportZip(c: AppContainer): ByteArray = withContext(Dispatchers.Default) {
    val diary = c.diary.all()
    val weights = c.weights.all()
    val days = c.days.all().associateBy { it.id }
    val profile = c.profiles.get()
    val burn = c.burn.observe().first()
    val trend = burn?.days?.associate { it.date.toString() to it.trendKg }.orEmpty()
    val byDate = diary.groupBy { it.entity.date }
    val dates = (byDate.keys + days.keys).sorted()
    val files = mapOf(
        "diary.csv" to Export.diaryCsv(diary.map { i ->
            val e = i.entity
            Export.DiaryRow(e.date, e.meal, e.foodName, e.brand, e.quantity, e.servingLabel, e.grams, i.nutrients)
        }),
        "days.csv" to Export.daysCsv(dates.map { d ->
            val day = days[d]
            Export.DayRow(
                d, day?.complete == true, day?.note, day?.steps, day?.activeKcal,
                runCatching { profile.calorieTargetOn(LocalDate.parse(d)) }.getOrNull(),
                sumNutrients(byDate[d].orEmpty().map { it.nutrients }),
            )
        }),
        "weights.csv" to Export.weightsCsv(weights.map { Export.WeightRow(it.date, it.weightKg, it.bodyFatPct, trend[it.date], it.note) }),
        "profile.json" to Http.json.encodeToString(com.nutrime.app.model.Profile.serializer(), profile),
        "nutrime.json" to Http.json.encodeToString(
            ExportRecords.serializer(),
            ExportRecords(
                diary.map { it.entity }, c.db.foods().all(), weights,
                listOfNotNull(c.db.profile().get()), c.days.all(), c.meals.all(),
            ),
        ),
        "README.txt" to "NutriMe export, ${LocalDate.now()}.\r\n" +
            "diary.csv: every food you logged, with nutrients for the amount eaten.\r\n" +
            "days.csv: daily totals, targets, notes, steps and active energy.\r\n" +
            "weights.csv: weigh-ins and the trend weight.\r\n" +
            "nutrime.json: every record, to restore with Import data in the NutriMe web app.\r\n",
    )
    Export.zip(files)
}
