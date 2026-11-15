package jp.linkserver.nittcsc.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.linkserver.nittcsc.MainActivity
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.logic.SCHOOL_DEPARTURE_DEFAULT_MINUTES
import jp.linkserver.nittcsc.logic.SchoolDepartureDelivery
import jp.linkserver.nittcsc.logic.normalizeSchoolDepartureMinutes
import jp.linkserver.nittcsc.logic.schoolDepartureDeliveryDecision
import jp.linkserver.nittcsc.logic.schoolDepartureNotificationTime
import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 「登校前通知」: その日の最初の授業開始の N 分前に知らせる。
 *
 * 端末ごとの通知設定として SharedPreferences に保存する（同期・DBスキーマの対象外）。
 * AlarmManager（正確なアラーム）+ WorkManager（フォールバック）の併用は他の通知と同じ方針。
 */
object SchoolDepartureReminder {
    private const val TAG = "SchoolDeparture"
    private const val PREFS = "school_departure_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MINUTES = "minutes_before"
    private const val KEY_LAST_DELIVERED_DATE = "last_delivered_date"
    private const val CHANNEL_ID = "school_departure"
    private const val ACTION_ALARM = "jp.linkserver.nittcsc.SCHOOL_DEPARTURE_ALARM"
    private const val WORK_TAG = "school_departure_reminder"
    internal const val EXTRA_DATE = "date"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun minutesBefore(context: Context): Int =
        normalizeSchoolDepartureMinutes(
            prefs(context).getInt(KEY_MINUTES, SCHOOL_DEPARTURE_DEFAULT_MINUTES)
        )

    /** 設定を保存する。呼び出し側で [rescheduleAll] を呼ぶこと。 */
    fun saveSettings(context: Context, enabled: Boolean, minutesBefore: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_MINUTES, normalizeSchoolDepartureMinutes(minutesBefore))
            .remove(KEY_LAST_DELIVERED_DATE)
            .apply()
    }

    suspend fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        val today = LocalDate.now()
        val dates = (0..ReminderSchedulingPolicy.LESSON_ALARM_HORIZON_DAYS).map { today.plusDays(it) }
        // 設定変更・時間割変更で古い予約が残らないよう、窓内を一度すべて取り消してから組み直す。
        dates.forEach { cancel(appContext, it) }
        if (!isEnabled(appContext)) return
        val snapshot = loadSnapshot(appContext) ?: return
        val minutes = minutesBefore(appContext)
        val now = LocalDateTime.now()
        dates.forEach { date ->
            if (date.isAfter(snapshot.settings.termEnd)) return@forEach
            val first = firstLesson(date, snapshot) ?: return@forEach
            val notificationAt = schoolDepartureNotificationTime(date, first.slot.start, minutes)
            if (!notificationAt.isAfter(now)) return@forEach
            val exact = ExactReminderAlarmScheduler.schedule(
                appContext,
                notificationAt,
                alarmPendingIntent(appContext, date),
                debugLabel = "school_departure_$date"
            )
            val fallbackAt = if (exact) notificationAt.plusMinutes(1) else notificationAt
            val delayMillis = Duration.between(now, fallbackAt).toMillis().coerceAtLeast(0L)
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                workName(date),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SchoolDepartureReminderWorker>()
                    .setInputData(Data.Builder().putString(EXTRA_DATE, date.toString()).build())
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .addTag(WORK_TAG)
                    .build()
            )
        }
    }

    /** アラーム/ワーカー共通の配信。二重配信は日付単位の記録で防ぐ。 */
    internal suspend fun deliver(context: Context, date: LocalDate, source: String): Boolean {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) return false
        if (prefs(appContext).getString(KEY_LAST_DELIVERED_DATE, null) == date.toString()) return false

        // 通知は待たせず、同期は同時に走らせる。
        CloudFileSyncManager.syncAlongsideNotification(appContext, "school_departure_$source")

        val snapshot = loadSnapshot(appContext) ?: return false
        val first = firstLesson(date, snapshot) ?: return false
        val notificationAt = schoolDepartureNotificationTime(date, first.slot.start, minutesBefore(appContext))
        val lessonStart = LocalDateTime.of(date, first.slot.start)
        val now = LocalDateTime.now()
        when (schoolDepartureDeliveryDecision(now, notificationAt, lessonStart)) {
            SchoolDepartureDelivery.TOO_LATE -> {
                ReminderDebug.log("school departure skipped date=$date source=$source reason=too_late")
                return false
            }
            SchoolDepartureDelivery.TOO_EARLY -> {
                ReminderDebug.log("school departure skipped date=$date source=$source reason=too_early")
                return false
            }
            SchoolDepartureDelivery.DELIVER -> Unit
        }

        createChannel(appContext)
        val remainingMinutes = Duration.between(now, lessonStart).toMinutes().coerceAtLeast(0L).toInt()
        val location = first.lesson.location.orEmpty()
        val body = appContext.getString(
            R.string.school_departure_notification_body,
            first.slot.label,
            first.lesson.subject,
            first.slot.start.toString(),
            remainingMinutes
        )
        val bigText = if (location.isBlank()) body else {
            body + "\n" + appContext.getString(R.string.school_departure_notification_location, location)
        }
        val notificationId = notificationId(date)
        val openApp = PendingIntent.getActivity(
            appContext,
            notificationId,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_school)
            .setContentTitle(appContext.getString(R.string.school_departure_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        val posted = NotificationManagerCompat.from(appContext)
            .notifyIfAllowed(appContext, notificationId, notification)
        if (posted) {
            prefs(appContext).edit().putString(KEY_LAST_DELIVERED_DATE, date.toString()).apply()
        }
        ReminderDebug.log("school departure finished date=$date source=$source posted=$posted")
        return posted
    }

    private fun firstLesson(date: LocalDate, snapshot: NotificationScheduleSnapshot): ScheduledLesson? =
        entriesForDate(date, snapshot)
            .filter { it.lesson.subject.isNotBlank() }
            .minByOrNull { it.slot.start }

    private fun cancel(context: Context, date: LocalDate) {
        ExactReminderAlarmScheduler.cancel(
            context,
            alarmPendingIntent(context, date),
            debugLabel = "school_departure_$date"
        )
        WorkManager.getInstance(context).cancelUniqueWork(workName(date))
    }

    private fun alarmPendingIntent(context: Context, date: LocalDate): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SchoolDepartureReminderReceiver::class.java).apply {
                action = ACTION_ALARM
                // data が日付ごとに異なるため、PendingIntent も日付ごとに独立する。
                data = Uri.parse("nittcsc://school-departure/$date")
                putExtra(EXTRA_DATE, date.toString())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun workName(date: LocalDate) = "school_departure_$date"

    internal fun notificationId(date: LocalDate): Int =
        0x5C000000 or (date.toEpochDay().toInt() and 0x00FFFFFF)

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.school_departure_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class SchoolDepartureReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = runCatching {
            LocalDate.parse(intent.getStringExtra(SchoolDepartureReminder.EXTRA_DATE).orEmpty())
        }.getOrNull() ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SchoolDepartureReminder.deliver(appContext, date, "alarm")
            } catch (error: Throwable) {
                ReminderDebug.warn("school departure alarm failed date=$date", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class SchoolDepartureReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val date = runCatching {
            LocalDate.parse(inputData.getString(SchoolDepartureReminder.EXTRA_DATE).orEmpty())
        }.getOrNull() ?: return Result.success()
        return try {
            SchoolDepartureReminder.deliver(applicationContext, date, "worker")
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ReminderDebug.warn("school departure worker failed date=$date", error)
            Result.success()
        }
    }
}
