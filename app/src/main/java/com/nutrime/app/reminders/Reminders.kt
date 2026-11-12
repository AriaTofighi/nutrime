package com.nutrime.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nutrime.app.MainActivity
import com.nutrime.app.NutriMeApplication
import com.nutrime.app.R
import com.nutrime.app.data.ReminderSettings
import com.nutrime.app.model.AdaptiveBurn
import com.nutrime.app.model.Meal
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Local reminders: a meal not logged by its usual time, a morning weigh-in, and the weekly check-in.
 * A worker checks every 30 minutes and sends each reminder at most once a day.
 */
object Reminders {
    private const val CHANNEL_LOG = "logging"
    private const val CHANNEL_CHECKIN = "checkin"
    private val DEFAULT_MEAL_MINUTES = listOf(9 * 60 + 30, 13 * 60 + 30, 19 * 60 + 30, 16 * 60)
    private const val EARLIEST = 7 * 60
    private const val LATEST = 21 * 60 + 30

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LOG, "Logging reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CHECKIN, "Weekly check-in", NotificationManager.IMPORTANCE_LOW))
    }

    fun schedule(context: Context, on: Boolean) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!on) { wm.cancelUniqueWork("reminders"); return }
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(30, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The usual time a meal is logged: the median over the last 30 days, or a default with fewer than 5 samples. */
    fun usualMinutes(createdAt: List<Long>, meal: Int, zone: ZoneId = ZoneId.systemDefault()): Int {
        val mins = createdAt.map { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let { t -> t.hour * 60 + t.minute } }.sorted()
        return if (mins.size < 5) DEFAULT_MEAL_MINUTES[meal] else mins[mins.size / 2]
    }

    /** What is due now: (kind, title, text, link). */
    suspend fun due(context: Context, now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): List<Reminder> {
        val c = (context.applicationContext as NutriMeApplication).container
        val r: ReminderSettings = c.settings.reminders.first()
        if (!r.any) return emptyList()
        val sent = c.settings.remindersSent()
        val nowMin = now.hour * 60 + now.minute
        val out = mutableListOf<Reminder>()
        if (r.meals.isNotEmpty()) {
            val todayEntries = c.db.diary().getDay(today.toString())
            val recent = c.diary.observeRange(today.minusDays(30), today.minusDays(1)).first()
            for (meal in r.meals) {
                val kind = "meal$meal"
                if ("$today:$kind" in sent || todayEntries.any { it.meal == meal && !it.deleted }) continue
                val usual = usualMinutes(recent.filter { it.entity.meal == meal }.map { it.entity.createdAt }, meal)
                val at = (usual + 45).coerceIn(EARLIEST, LATEST)
                if (nowMin in at until at + 120) {
                    val name = Meal.entries[meal].label
                    out += Reminder(kind, "$name not logged yet", "Add your ${name.lowercase()} while you remember it.", "nutrime://add?meal=$meal")
                }
            }
        }
        if (r.weighIn && today.dayOfWeek.value in r.weighDays && "$today:weigh" !in sent && nowMin in r.weighMinutes until r.weighMinutes + 180) {
            if (c.db.weights().onDate(today.toString()).isEmpty()) {
                out += Reminder("weigh", "Time to weigh in", "A weigh-in each morning keeps your trend and daily burn accurate.", "nutrime://weight")
            }
        }
        if (r.checkIn && "$today:checkin" !in sent && nowMin >= 9 * 60) {
            val p = c.profiles.get()
            val est = c.burn.observe(today).first()
            if (AdaptiveBurn.checkInDue(p, est, today)) {
                out += Reminder("checkin", "Your weekly check-in is ready", "NutriMe learned more about your daily burn.", "nutrime://diary")
            }
        }
        return out
    }

    fun notify(context: Context, r: Reminder) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(r.link))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, r.kind.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, if (r.kind == "checkin") CHANNEL_CHECKIN else CHANNEL_LOG)
            .setSmallIcon(R.drawable.ic_stat_nutrime)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(r.kind.hashCode(), n) } catch (_: SecurityException) { }
    }
}

data class Reminder(val kind: String, val title: String, val text: String, val link: String)

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as NutriMeApplication).container
        val today = LocalDate.now().toString()
        Reminders.due(applicationContext).forEach { r ->
            Reminders.notify(applicationContext, r)
            c.settings.markReminderSent(today, r.kind)
        }
        return Result.success()
    }
}
