package jp.linkserver.nittcsc.widget

import android.content.Context

/**
 * 配置済みウィジェットをまとめて更新するユーティリティ。
 * Glance のセッション/WorkManager には頼らず、[DirectWidgetRenderer] が直接描画する。
 */
object WidgetUpdater {
    suspend fun updateTaskWidgets(context: Context) {
        DirectWidgetRenderer.updateWidgets(context) { it.isTask }
    }

    suspend fun updateAll(context: Context) {
        DirectWidgetRenderer.updateWidgets(context) { true }
    }
}
