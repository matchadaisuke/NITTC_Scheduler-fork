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
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.EffectiveSchoolSchedule
import jp.linkserver.nittcsc.data.LessonNotificationCustomization
import jp.linkserver.nittcsc.data.LessonNotificationTrigger
import jp.linkserver.nittcsc.data.SchoolTripTemplateValues
import jp.linkserver.nittcsc.data.departureTime
import jp.linkserver.nittcsc.data.lessonNotificationCustomization
import jp.linkserver.nittcsc.data.renderSchoolTripTemplate
import jp.linkserver.nittcsc.logic.SCHOOL_TRIP_MAX_TRIGGERS
import jp.linkserver.nittcsc.logic.SchoolTripDelivery
import jp.linkserver.nittcsc.logic.schoolTripDeliveryDecision
import jp.linkserver.nittcsc.logic.schoolTripNotificationTime
import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** 「登校前通知」「下校前通知」。授業前通知と同じ形（最大2つのタイミング＋テンプレート）で設定する。 */
enum class SchoolTripKind(val key: String) {
    ARRIVAL("arrival"),
    DEPARTURE("departure");

    fun triggers(config: LessonNotificationCustomization): List<LessonNotificationTrigger> = when (this) {
        ARRIVAL -> config.arrivalTriggers
        DEPARTURE -> config.departureTriggers
    }.take(SCHOOL_TRIP_MAX_TRIGGERS)

    fun titleTemplate(config: LessonNotificationCustomization): String = when (this) {
        ARRIVAL -> config.arrivalTitleTemplate
        DEPARTURE -> config.departureTitleTemplate
    }

    fun bodyTemplate(config: LessonNotificationCustomization): String = when (this) {
        ARRIVAL -> config.arrivalBodyTemplate
        DEPARTURE -> config.departureBodyTemplate
    }

    /** 基準時刻。空欄（自動）の場合も含め、入力済みの登校時刻/下校時刻をそのまま使う。 */
    fun baseTime(schedule: EffectiveSchoolSchedule, date: LocalDate): LocalTime = when (this) {
        ARRIVAL -> schedule.arrivalTime(date)
        DEPARTURE -> schedule.settings.departureTime()
    }

    companion object {
        fun fromKey(key: String?): SchoolTripKind? = entries.firstOrNull { it.key == key }
    }
}

object SchoolTripReminder {
    private const val PREFS = "school_trip_reminder"
    private const val KEY_DELIVERED = "delivered_keys"
    private const val CHANNEL_ID = "school_trip"
    private const val ACTION_ALARM = "jp.linkserver.nittcsc.SCHOOL_TRIP_ALARM"
    private const val WORK_TAG = "school_trip_reminder"
    private const val MAX_LEDGER = 40
    internal const val EXTRA_KIND = "kind"
    internal const val EXTRA_DATE = "date"
    internal const val EXTRA_INDEX = "index"

    /** 設定変更・時間割変更・起動時に呼ぶ。窓内の予約をいったん全部消してから組み直す。 */
    suspend fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        val today = LocalDate.now()
        val dates = (0..ReminderSchedulingPolicy.LESSON_ALARM_HORIZON_DAYS).map { today.plusDays(it) }
        for (kind in SchoolTripKind.entries) {
            for (date in dates) {
                for (index in 0 until SCHOOL_TRIP_MAX_TRIGGERS) cancel(appContext, kind, date, index)
            }
        }
        val schedule = EffectiveSchoolSchedule.load(AppDatabase.getInstance(appContext).schedulerDao()) ?: return
        val config = schedule.settings.lessonNotificationCustomization()
        val now = LocalDateTime.now()
        for (kind in SchoolTripKind.entries) {
            kind.triggers(config).forEachIndexed { index, trigger ->
                if (!trigger.enabled) return@forEachIndexed
                for (date in dates) {
                    // 休日・休講日（授業が1コマも無い日）は通知しない。
                    if (schedule.lessonsOn(date).isEmpty()) continue
                    val notificationAt = schoolTripNotificationTime(date, kind.baseTime(schedule, date), trigger.minutesBefore)
                    if (!notificationAt.isAfter(now)) continue
                    val exact = ExactReminderAlarmScheduler.schedule(
                        appContext, notificationAt, alarmPendingIntent(appContext, kind, date, index),
                        debugLabel = "school_trip_${kind.key}_${date}_$index"
                    )
                    val fallbackAt = if (exact) notificationAt.plusMinutes(1) else notificationAt
                    val delayMillis = Duration.between(now, fallbackAt).toMillis().coerceAtLeast(0L)
                    WorkManager.getInstance(appContext).enqueueUniqueWork(
                        workName(kind, date, index),
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<SchoolTripReminderWorker>()
                            .setInputData(
                                Data.Builder()
                                    .putString(EXTRA_KIND, kind.key)
                                    .putString(EXTRA_DATE, date.toString())
                                    .putInt(EXTRA_INDEX, index)
                                    .build()
                            )
                            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                            .addTag(WORK_TAG)
                            .build()
                    )
                }
            }
        }
    }

    /** アラーム/ワーカー共通の配信。二重配信は (種類, 日付, タイミング) 単位の記録で防ぐ。 */
    internal suspend fun deliver(
        context: Context,
        kind: SchoolTripKind,
        date: LocalDate,
        index: Int,
        source: String
    ): Boolean {
        val appContext = context.applicationContext
        val ledgerKey = "${kind.key}|$date|$index"
        if (wasDelivered(appContext, ledgerKey)) return false

        // 通知は待たせず、同期は同時に走らせる。
        CloudFileSyncManager.syncAlongsideNotification(appContext, "school_trip_${kind.key}_$source")

        val schedule = EffectiveSchoolSchedule.load(AppDatabase.getInstance(appContext).schedulerDao()) ?: return false
        val config = schedule.settings.lessonNotificationCustomization()
        val trigger = kind.triggers(config).getOrNull(index)?.takeIf { it.enabled } ?: return false
        val lessons = schedule.lessonsOn(date).sortedBy { it.start }
        if (lessons.isEmpty()) return false

        val baseTime = kind.baseTime(schedule, date)
        val notificationAt = schoolTripNotificationTime(date, baseTime, trigger.minutesBefore)
        val now = LocalDateTime.now()
        when (schoolTripDeliveryDecision(now, notificationAt, LocalDateTime.of(date, baseTime))) {
            SchoolTripDelivery.TOO_LATE, SchoolTripDelivery.TOO_EARLY -> {
                ReminderDebug.log("school trip skipped kind=${kind.key} date=$date source=$source")
                return false
            }
            SchoolTripDelivery.DELIVER -> Unit
        }

        val first = lessons.first()
        val tomorrowFirst = schedule.lessonsOn(date.plusDays(1)).minByOrNull { it.start }
        val values = SchoolTripTemplateValues(
            minutes = trigger.minutesBefore,
            time = baseTime.toString(),
            firstSubject = first.content.subject,
            firstTeacher = first.content.teacher,
            firstLocation = first.content.location.orEmpty(),
            firstPeriod = first.slotLabel,
            firstStartTime = first.start.toString(),
            lessonCount = lessons.size,
            tomorrowFirstSubject = tomorrowFirst?.content?.subject ?: "なし",
            tomorrowFirstLocation = tomorrowFirst?.content?.location.orEmpty(),
            tomorrowFirstPeriod = tomorrowFirst?.slotLabel.orEmpty(),
            tomorrowFirstStartTime = tomorrowFirst?.start?.toString().orEmpty()
        )
        val title = renderSchoolTripTemplate(kind.titleTemplate(config), values).ifBlank {
            appContext.getString(
                if (kind == SchoolTripKind.ARRIVAL) R.string.school_arrival_notification_title
                else R.string.school_departure_notification_title
            )
        }
        val body = renderSchoolTripTemplate(kind.bodyTemplate(config), values)

        createChannel(appContext)
        val notificationId = notificationId(kind, date, index)
        val openApp = PendingIntent.getActivity(
            appContext, notificationId,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_school)
            .setContentTitle(title)
            // Wear OS は BigTextStyle を展開せず contentText だけ橋渡しするため、全文を入れる。
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        val posted = NotificationManagerCompat.from(appContext).notifyIfAllowed(appContext, notificationId, notification)
        if (posted) markDelivered(appContext, ledgerKey)
        ReminderDebug.log("school trip finished kind=${kind.key} date=$date index=$index source=$source posted=$posted")
        return posted
    }

    private fun cancel(context: Context, kind: SchoolTripKind, date: LocalDate, index: Int) {
        ExactReminderAlarmScheduler.cancel(
            context, alarmPendingIntent(context, kind, date, index),
            debugLabel = "school_trip_${kind.key}_${date}_$index"
        )
        WorkManager.getInstance(context).cancelUniqueWork(workName(kind, date, index))
    }

    private fun alarmPendingIntent(context: Context, kind: SchoolTripKind, date: LocalDate, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SchoolTripReminderReceiver::class.java).apply {
                action = ACTION_ALARM
                // data が (種類, 日付, タイミング) ごとに異なるため、PendingIntent も独立する。
                data = Uri.parse("nittcsc://school-trip/${kind.key}/$date/$index")
                putExtra(EXTRA_KIND, kind.key)
                putExtra(EXTRA_DATE, date.toString())
                putExtra(EXTRA_INDEX, index)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun workName(kind: SchoolTripKind, date: LocalDate, index: Int) = "school_trip_${kind.key}_${date}_$index"

    internal fun notificationId(kind: SchoolTripKind, date: LocalDate, index: Int): Int =
        0x5C000000 or ((date.toEpochDay().toInt() and 0x0000FFFF) shl 8) or (kind.ordinal shl 4) or index

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.school_trip_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun wasDelivered(context: Context, key: String): Boolean =
        prefs(context).getStringSet(KEY_DELIVERED, emptySet()).orEmpty().contains(key)

    private fun markDelivered(context: Context, key: String) {
        val current = prefs(context).getStringSet(KEY_DELIVERED, emptySet()).orEmpty()
        // 日付は文字列の辞書順＝時系列なので、古いものから捨てて肥大化を防ぐ。
        val next = (current + key).sortedBy { it.substringAfter('|') }.takeLast(MAX_LEDGER).toSet()
        prefs(context).edit().putStringSet(KEY_DELIVERED, next).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class SchoolTripReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = SchoolTripKind.fromKey(intent.getStringExtra(SchoolTripReminder.EXTRA_KIND)) ?: return
        val date = runCatching { LocalDate.parse(intent.getStringExtra(SchoolTripReminder.EXTRA_DATE).orEmpty()) }.getOrNull() ?: return
        val index = intent.getIntExtra(SchoolTripReminder.EXTRA_INDEX, 0)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SchoolTripReminder.deliver(appContext, kind, date, index, "alarm")
            } catch (error: Throwable) {
                ReminderDebug.warn("school trip alarm failed kind=${kind.key} date=$date", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class SchoolTripReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val kind = SchoolTripKind.fromKey(inputData.getString(SchoolTripReminder.EXTRA_KIND)) ?: return Result.success()
        val date = runCatching { LocalDate.parse(inputData.getString(SchoolTripReminder.EXTRA_DATE).orEmpty()) }.getOrNull()
            ?: return Result.success()
        val index = inputData.getInt(SchoolTripReminder.EXTRA_INDEX, 0)
        return try {
            SchoolTripReminder.deliver(applicationContext, kind, date, index, "worker")
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ReminderDebug.warn("school trip worker failed kind=${kind.key} date=$date", error)
            Result.success()
        }
    }
}
