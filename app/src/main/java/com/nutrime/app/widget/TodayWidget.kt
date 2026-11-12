package com.nutrime.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nutrime.app.MainActivity
import com.nutrime.app.NutriMeApplication
import com.nutrime.app.model.Dri
import com.nutrime.app.model.N
import com.nutrime.app.model.sumNutrients
import com.nutrime.app.ui.theme.DesignTokens
import kotlinx.coroutines.flow.first
import java.time.LocalDate

private data class WidgetData(val eaten: Double, val target: Int, val protein: Pair<Double, Double>, val carbs: Pair<Double, Double>, val fat: Pair<Double, Double>)

/** Home screen widget: calories left today, three macro bars, and a + button. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as NutriMeApplication).container
        val today = LocalDate.now()
        val items = c.diary.observeDay(today).first()
        val profile = c.profiles.get()
        val totals = sumNutrients(items.map { it.nutrients })
        val targets = Dri.targets(profile, today)
        fun pair(id: Int) = (totals[id] ?: 0.0) to (targets[id]?.min ?: 0.0)
        val data = WidgetData(totals[N.ENERGY] ?: 0.0, profile.calorieTargetOn(today), pair(N.PROTEIN), pair(N.CARBS), pair(N.FAT))
        provideContent { Content(context, data) }
    }

    companion object {
        /** Redraw every placed widget (after a diary or profile change, and after sync). */
        suspend fun refresh(context: Context) {
            val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(TodayWidget::class.java) }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) runCatching { TodayWidget().updateAll(context) }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

private fun color(light: Color, dark: Color) = ColorProvider(day = light, night = dark)

private fun open(context: Context, uri: String) = actionStartActivity(
    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
)

@Composable
private fun Content(context: Context, d: WidgetData) {
    val L = DesignTokens.Light
    val D = DesignTokens.Dark
    val surface = color(L.surface, D.surface)
    val text = color(L.text, D.text)
    val sub = color(L.text.copy(alpha = 0.6f), D.text.copy(alpha = 0.6f))
    val track = color(L.text.copy(alpha = 0.08f), D.text.copy(alpha = 0.12f))
    val accent = color(L.protein, D.protein)
    val remaining = d.target - d.eaten
    val over = remaining < 0
    val wide = LocalSize.current.width >= 230.dp
    Box(
        GlanceModifier.fillMaxSize().background(surface).cornerRadius(20.dp).padding(14.dp).clickable(open(context, "nutrime://diary")),
    ) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Text("%,.0f".format(kotlin.math.abs(remaining)), style = TextStyle(color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold))
                Text(if (over) "kcal over" else "kcal left", style = TextStyle(color = if (over) color(L.warning, D.warning) else sub, fontSize = 12.sp))
                Spacer(GlanceModifier.height(8.dp))
                LinearProgressIndicator(
                    progress = (d.eaten / d.target.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                    color = if (over) color(L.warning, D.warning) else accent, backgroundColor = track,
                )
                Spacer(GlanceModifier.height(6.dp))
                Text("%,.0f of %,d".format(d.eaten, d.target), style = TextStyle(color = sub, fontSize = 11.sp))
            }
            if (wide) {
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Macro("Protein", d.protein, color(L.protein, D.protein), text, track)
                    Macro("Carbs", d.carbs, color(L.carbs, D.carbs), text, track)
                    Macro("Fat", d.fat, color(L.fat, D.fat), text, track)
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            Box(
                GlanceModifier.size(40.dp).background(accent).cornerRadius(20.dp).clickable(open(context, "nutrime://add")),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(color = surface, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun Macro(name: String, v: Pair<Double, Double>, bar: androidx.glance.unit.ColorProvider, text: androidx.glance.unit.ColorProvider, track: androidx.glance.unit.ColorProvider) {
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("$name %.0f / %.0f g".format(v.first, v.second), style = TextStyle(color = text, fontSize = 11.sp))
        LinearProgressIndicator(
            progress = if (v.second > 0) (v.first / v.second).toFloat().coerceIn(0f, 1f) else 0f,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp), color = bar, backgroundColor = track,
        )
    }
}
