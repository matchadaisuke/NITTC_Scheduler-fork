package jp.linkserver.nittcsc.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import jp.linkserver.nittcsc.MainActivity

/** データ読み込みに失敗したときの案内。タップでアプリを開き、再読み込みのきっかけにする。 */
@Composable
internal fun WidgetLoadErrorContent() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(28.dp)
            .background(GlanceTheme.colors.background)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
            .padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Text(
            text = "ウィジェットのデータを読み込めません",
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        )
        Text(
            text = "タップしてアプリを開くと再読み込みします",
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        )
        WidgetDataHelper.lastLoadError?.let { reason ->
            Text(
                text = reason,
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
