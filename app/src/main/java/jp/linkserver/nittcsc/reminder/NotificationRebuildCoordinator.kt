package jp.linkserver.nittcsc.reminder

import android.content.Context
import android.util.Log
import jp.linkserver.nittcsc.widget.WidgetUpdater

/**
 * Single entry point for restoring reminder schedules.
 *
 * All future settings changes, imports and cloud sync restores should call this
 * coordinator instead of registering alarms directly.
 */
object NotificationRebuildCoordinator {
    private const val TAG = "NotificationRebuild"

    suspend fun rebuild(context: Context, reason: String, forceWorkUpdate: Boolean = false) {
        Log.d(TAG, "Rebuilding notification schedules: $reason")

        val appContext = context.applicationContext
        TaskReminderWorker.rescheduleAll(appContext, forceWorkUpdate)
        PlanReminderWorker.rescheduleAll(appContext)
        LessonStartNotificationWorker.rescheduleAll(appContext, forceWorkUpdate)
        SchoolTripReminder.rescheduleAll(appContext)
        // 同期・インポート・起動で時間割/課題が変わったら、ウィジェットも必ず追従させる。
        runCatching { WidgetUpdater.updateAll(appContext) }
            .onFailure { Log.w(TAG, "Widget update after rebuild failed", it) }
    }
}
