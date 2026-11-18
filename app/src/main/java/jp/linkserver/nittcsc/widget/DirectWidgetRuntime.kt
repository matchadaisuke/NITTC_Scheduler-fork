package jp.linkserver.nittcsc.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/*
 * ウィジェットの描画方式について
 *
 * Glance 標準（GlanceAppWidgetReceiver → WorkManager → Glance セッション）は、途中のどこかが
 * 動かないと初期表示（ぐるぐる）のまま永遠に更新されない。既知の原因:
 *  - goAsync() が失敗する端末（Glance は失敗をログに出すだけ）
 *  - 描画セッションが残って後続の更新が捨てられる
 *  - リリースビルドで R8 が WorkManager のコンストラクタを削除する
 *
 * このアプリのウィジェットは静的表示（タップでアプリを開くだけ）なので、Glance の Composable
 * だけを使い、描画はレシーバ内で GlanceRemoteViews により直接行って updateAppWidget する。
 * WorkManager・セッション・Glance のレシーバ機構には一切依存しない。
 */

/** Glance の Composable で描くウィジェット。描画は [DirectWidgetRenderer] が行う。 */
abstract class DirectGlanceWidget {
    /** [data] が null のときは読み込み失敗の案内を出す。 */
    @Composable
    @GlanceComposable
    abstract fun Body(data: WidgetData?)
}

/** 直接描画するウィジェット用レシーバの基底。マニフェストの受信設定は従来どおり APPWIDGET_UPDATE。 */
abstract class DirectWidgetReceiver : AppWidgetProvider() {
    abstract val widget: DirectGlanceWidget

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        DirectWidgetRenderer.renderInBackground(this, context, widget, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // リサイズ時は新しいサイズで描き直す。
        DirectWidgetRenderer.renderInBackground(this, context, widget, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        DirectWidgetRenderer.forget(appWidgetIds)
    }
}

internal class WidgetKind(
    val receiver: Class<out DirectWidgetReceiver>,
    val widget: () -> DirectGlanceWidget,
    val isTask: Boolean
)

internal val ALL_WIDGET_KINDS: List<WidgetKind> = listOf(
    WidgetKind(TimetableSmallWidgetReceiver::class.java, ::TimetableSmallWidget, isTask = false),
    WidgetKind(TimetableHugeWidgetReceiver::class.java, ::TimetableHugeWidget, isTask = false),
    WidgetKind(NextLessonWidgetReceiver::class.java, ::NextLessonWidget, isTask = false),
    WidgetKind(TaskSmallWidgetReceiver::class.java, ::TaskSmallWidget, isTask = true),
    WidgetKind(TaskHugeWidgetReceiver::class.java, ::TaskHugeWidget, isTask = true)
)

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
internal object DirectWidgetRenderer {
    private const val TAG = "DirectWidget"
    private const val COMPOSE_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 同時描画を直列化し、DB 読み込みと composition の競合を避ける。
    private val renderMutex = Mutex()

    // GlanceRemoteViews は「ホストビューごとに同じインスタンスを使い回す」前提。
    private val remoteViewsByWidgetId = ConcurrentHashMap<Int, GlanceRemoteViews>()

    fun renderInBackground(
        receiver: BroadcastReceiver,
        context: Context,
        widget: DirectGlanceWidget,
        appWidgetIds: IntArray
    ) {
        val appContext = context.applicationContext
        // goAsync() が失敗する端末があっても描画は続行する。
        val pending = runCatching { receiver.goAsync() }.getOrNull()
        scope.launch {
            try {
                appWidgetIds.forEach { renderOne(appContext, widget, it) }
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }

    fun forget(appWidgetIds: IntArray) {
        appWidgetIds.forEach { remoteViewsByWidgetId.remove(it) }
    }

    /** 1 つのウィジェットを描画して AppWidgetManager へ反映する。失敗しても例外は投げない（キャンセルを除く）。 */
    suspend fun renderOne(context: Context, widget: DirectGlanceWidget, appWidgetId: Int) {
        renderMutex.withLock {
            val manager = AppWidgetManager.getInstance(context)
            val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull() ?: Bundle()
            val size = widgetSize(context, manager, appWidgetId, options)
            val data = WidgetDataHelper.loadOrNull(context)

            val views: RemoteViews = (try {
                compose(appWidgetId, context, size, options) { widget.Body(data) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Widget composition failed id=$appWidgetId", error)
                WidgetDataHelper.lastLoadError = error::class.java.simpleName
                // 何も出さないより、原因名つきの案内を出す。
                runCatching {
                    compose(appWidgetId, context, size, options) {
                        GlanceTheme { WidgetLoadErrorContent() }
                    }
                }.getOrNull()
            }) ?: return@withLock

            try {
                manager.updateAppWidget(appWidgetId, views)
            } catch (error: Throwable) {
                Log.e(TAG, "updateAppWidget failed id=$appWidgetId", error)
            }
        }
    }

    private suspend fun compose(
        appWidgetId: Int,
        context: Context,
        size: DpSize,
        options: Bundle,
        content: @Composable @GlanceComposable () -> Unit
    ): RemoteViews {
        val glanceViews = remoteViewsByWidgetId.getOrPut(appWidgetId) { GlanceRemoteViews() }
        val result = withTimeoutOrNull(COMPOSE_TIMEOUT_MS) {
            glanceViews.compose(
                context = context,
                size = size,
                appWidgetOptions = options,
                content = content
            )
        } ?: error("Widget composition timed out after ${COMPOSE_TIMEOUT_MS}ms")
        return result.remoteViews
    }

    private fun widgetSize(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        options: Bundle
    ): DpSize {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        if (width > 0 && height > 0) return DpSize(width.dp, height.dp)
        // 初回など options が空のときは、プロバイダ定義の最小サイズ（px）から求める。
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val info = runCatching { manager.getAppWidgetInfo(appWidgetId) }.getOrNull()
        val widthPx = info?.minWidth?.takeIf { it > 0 } ?: (250 * density).toInt()
        val heightPx = info?.minHeight?.takeIf { it > 0 } ?: (110 * density).toInt()
        return DpSize((widthPx / density).dp, (heightPx / density).dp)
    }

    /** 配置済みの全ウィジェット（[filter] に合う種類のみ）を描き直す。 */
    suspend fun updateWidgets(context: Context, filter: (WidgetKind) -> Boolean) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        ALL_WIDGET_KINDS.filter(filter).forEach { kind ->
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(appContext, kind.receiver))
            }.getOrNull() ?: IntArray(0)
            if (ids.isEmpty()) return@forEach
            val widget = kind.widget()
            ids.forEach { renderOne(appContext, widget, it) }
        }
    }
}
