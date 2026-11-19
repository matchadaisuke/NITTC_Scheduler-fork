package jp.linkserver.nittcsc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.SchedulerRepository
import jp.linkserver.nittcsc.data.UiDesignPreferences
import jp.linkserver.nittcsc.reminder.NotificationRebuildCoordinator
import jp.linkserver.nittcsc.reminder.TaskReminderNotifier
import jp.linkserver.nittcsc.sync.CloudFileSyncManager
import jp.linkserver.nittcsc.sync.LocalSyncManager
import jp.linkserver.nittcsc.sync.NearbySyncManager
import jp.linkserver.nittcsc.sync.NearbySyncPreferences
import jp.linkserver.nittcsc.ui.NittcSchedulerApp
import jp.linkserver.nittcsc.ui.theme.AppTheme
import jp.linkserver.nittcsc.update.createAppUpdates
import jp.linkserver.nittcsc.viewmodel.SchedulerViewModel
import jp.linkserver.nittcsc.viewmodel.SchedulerViewModelFactory
import jp.linkserver.nittcsc.widget.WidgetUpdateWorker
import jp.linkserver.nittcsc.widget.WidgetUpdater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val transferOpen = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == jp.linkserver.nittcsc.update.ApkUpdateProfile.ACTION_VIEW_TRANSFER) transferOpen.value++
    }

    private val viewModel: SchedulerViewModel by viewModels {
        val database = AppDatabase.getInstance(this)
        val repository = SchedulerRepository(database, UiDesignPreferences(this))
        val syncManager = LocalSyncManager(this, repository, database)
        val nearbySyncManager = NearbySyncManager(this, repository)
        SchedulerViewModelFactory(
            repository,
            syncManager,
            nearbySyncManager,
            createAppUpdates(this),
            nearbyStandbyEnabledInitially = !NearbySyncPreferences.suppressAutomaticPrompts(this)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskReminderNotifier.initializeNotificationChannel(this)
        // A preserved Android 15 decor can otherwise fit system bars again on recreation.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent.action == jp.linkserver.nittcsc.update.ApkUpdateProfile.ACTION_VIEW_TRANSFER) transferOpen.value++
        setContent {
            val uiDesignMode by viewModel.uiDesignMode.collectAsStateWithLifecycle()
            val appThemeMode by viewModel.appThemeMode.collectAsStateWithLifecycle()
            AppTheme(
                uiDesignMode = uiDesignMode,
                darkTheme = appThemeMode.isDark(isSystemInDarkTheme())
            ) {
                val request by transferOpen.collectAsStateWithLifecycle()
                NittcSchedulerApp(viewModel = viewModel, openTransferRequest = request)
            }
        }
        CloudFileSyncManager.start(this)
        WidgetUpdateWorker.schedule(this)
        lifecycleScope.launch {
            NotificationRebuildCoordinator.rebuild(this@MainActivity, "app_start")
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Deliver preserved insets before Compose's first measure can clamp restored scroll.
        val content = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.getRootWindowInsets(content)?.let { ViewCompat.dispatchApplyWindowInsets(content, it) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkForAppUpdates()
        // 年度切替を先に反映してから、通知・ウィジェットと同期を更新する
        lifecycleScope.launch {
            viewModel.refreshAcademicYear()
            jp.linkserver.nittcsc.reminder.NotificationScheduleCoordinator.refresh(this@MainActivity)
            WidgetUpdater.updateAll(this@MainActivity)
            // 画面に戻るたびに最新へ追従（直近30秒以内に成功していれば何もしない。失敗時は自動で再試行）。
            CloudFileSyncManager.syncSoon(this@MainActivity, "app_resume")
            viewModel.runAutoSync()
        }
    }

    override fun onStop() {
        super.onStop()
        // バックグラウンド移行・終了時に未送信の変更があれば送る（プロセス終了に備えWorkManagerにも積む）。
        CloudFileSyncManager.syncOnLeave(this)
    }

}
