package com.deeprows.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

// =============================================================
// NOTIFICATIONS: TRENDS, LATEST NEWS, SPORTS NEWS
// =============================================================
//
// A background job (WorkManager) runs every few hours and posts up
// to three notifications:
//
//   - Trending now   (Google Trends for the user's country)
//   - Latest news    (BBC, Al Jazeera, Guardian, DW ...)
//   - Sports news    (BBC Sport, ESPN, Sky Sports ...)
//
// A notification is only shown when its top stories changed since the
// last one, so users are never sent the same list twice. Each kind has
// its own channel (users can mute one in Android settings) and its own
// on/off switch in the app (Settings > Notifications).
//
// Tapping a notification opens the top story in the browser.
//
// =============================================================

object TrendNotifications {

    const val EXTRA_OPEN_URL = "open_url"

    private const val WORK_NAME = "deeprows_trend_alerts"
    private const val PREFS = "deeprows_browser"

    private class Alert(
        val kind: String,
        val channelId: String,
        val channelName: String,
        val channelDescription: String,
        val notificationId: Int,
        val prefKey: String
    )

    private val alerts = listOf(
        Alert(
            "trends",
            "deeprows_trending",
            "Trending now",
            "Alerts about what is trending right now",
            2001,
            "notif_trends"
        ),
        Alert(
            "news",
            "deeprows_latest_news",
            "Latest news",
            "Top headlines from around the world",
            2002,
            "notif_news"
        ),
        Alert(
            "sports",
            "deeprows_sports_news",
            "Sports news",
            "Latest sports headlines",
            2003,
            "notif_sports"
        )
    )

    fun createChannel(context: Context) {

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {

            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            alerts.forEach { alert ->

                val channel = NotificationChannel(
                    alert.channelId,
                    alert.channelName,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = alert.channelDescription
                }

                manager.createNotificationChannel(channel)
            }
        }
    }

    // Safe to call on every app start: KEEP leaves an already
    // scheduled job untouched.
    fun schedule(context: Context) {

        val request = PeriodicWorkRequestBuilder<TrendWorker>(
            6, TimeUnit.HOURS
        )
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    private fun canNotify(context: Context): Boolean {

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }

        return NotificationManagerCompat
            .from(context)
            .areNotificationsEnabled()
    }

    internal suspend fun checkAndNotify(context: Context) {

        if (!canNotify(context)) return

        createChannel(context)

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val repository = NewsRepository()

        alerts.forEach { alert ->

            // One failing feed must not stop the other alerts.
            try {

                if (!prefs.getBoolean(alert.prefKey, true)) {
                    return@forEach
                }

                val stories = when (alert.kind) {
                    "trends" -> {
                        val country = CountryProvider.getCountryCode(context)
                        repository.getGoogleTrends(
                            country.ifBlank { null },
                            5
                        )
                    }
                    "news" -> repository.getLatestNews(5)
                    else -> repository.getSportNews(5)
                }

                if (stories.isNotEmpty()) {
                    notifyStories(context, prefs, alert, stories)
                }

            } catch (_: Exception) {
            }
        }
    }

    private fun notifyStories(
        context: Context,
        prefs: android.content.SharedPreferences,
        alert: Alert,
        stories: List<NewsArticle>
    ) {

        val signatureKey = "last_signature_${alert.kind}"

        // Skip if the top stories are the same as the last alert.
        val signature = stories.take(3).joinToString("|") { it.title }

        if (prefs.getString(signatureKey, null) == signature) {
            return
        }

        val top = stories.first()

        val summary = stories
            .take(3)
            .mapIndexed { i, s -> "${i + 1}. ${s.title}" }
            .joinToString("\n")

        val title = when (alert.kind) {
            "trends" -> "\uD83D\uDD25 Trending now: ${top.title}"
            "news" -> "\uD83D\uDCF0 Latest news"
            else -> "\u26BD Sports news"
        }

        val text = when (alert.kind) {
            "trends" -> stories.drop(1).firstOrNull()?.title ?: ""
            else -> top.title
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (top.link.isNotBlank()) {
                putExtra(EXTRA_OPEN_URL, top.link)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            alert.notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            alert.channelId
        )
            .setSmallIcon(R.drawable.ic_stat_trend)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(summary)
            )
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                alert.notificationId,
                notification
            )
            prefs.edit().putString(signatureKey, signature).apply()
        } catch (_: SecurityException) {
            // Permission was revoked in the meantime.
        }
    }
}

class TrendWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            TrendNotifications.checkAndNotify(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
