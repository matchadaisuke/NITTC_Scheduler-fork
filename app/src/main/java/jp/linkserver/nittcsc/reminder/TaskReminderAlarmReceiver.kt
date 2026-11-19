package jp.linkserver.nittcsc.reminder

import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        if (taskId > 0L) {
            // 通知は待たせず、同期は同時に走らせる。
            CloudFileSyncManager.syncAlongsideNotification(context, "task_alarm")
            val kind = runCatching {
                jp.linkserver.nittcsc.logic.TaskReminderKind.valueOf(intent.getStringExtra(TaskReminderWorker.KEY_KIND).orEmpty())
            }.getOrDefault(jp.linkserver.nittcsc.logic.TaskReminderKind.MANUAL)
            TaskReminderWorker.enqueueNow(context.applicationContext, taskId, kind,
                intent.getStringExtra(TaskReminderWorker.KEY_TRIGGER))
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}
