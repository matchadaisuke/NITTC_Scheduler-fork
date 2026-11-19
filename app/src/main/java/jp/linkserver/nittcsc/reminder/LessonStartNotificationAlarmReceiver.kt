package jp.linkserver.nittcsc.reminder

import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

class LessonStartNotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = runCatching {
            LocalDate.parse(intent.getStringExtra(EXTRA_DATE).orEmpty())
        }.getOrNull() ?: return
        val slotIndex = intent.getIntExtra(EXTRA_SLOT_INDEX, -1)
        if (slotIndex < 0) return

        // 通知は待たせず、同期は同時に走らせる。
        CloudFileSyncManager.syncAlongsideNotification(context, "lesson_alarm")
        LessonStartNotificationWorker.enqueueNow(context.applicationContext, date, slotIndex,
            intent.getBooleanExtra(LessonStartNotificationWorker.KEY_LIVE, false),
            intent.getStringExtra(LessonStartNotificationWorker.KEY_SIGNATURE))
    }

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_SLOT_INDEX = "slot_index"
    }
}
