package jp.linkserver.nittcsc.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.logic.SCHOOL_DEPARTURE_MINUTES_RANGE
import jp.linkserver.nittcsc.reminder.SchoolDepartureReminder
import jp.linkserver.nittcsc.reminder.canPostAppNotifications
import kotlinx.coroutines.launch

/** 「登校前通知」（最初の授業の N 分前）の設定。端末ごとの設定として保存する。 */
@Composable
internal fun SchoolDepartureReminderSettingsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(SchoolDepartureReminder.isEnabled(context)) }
    var minutesText by remember { mutableStateOf(SchoolDepartureReminder.minutesBefore(context).toString()) }

    fun apply(newEnabled: Boolean, minutes: Int) {
        SchoolDepartureReminder.saveSettings(context, newEnabled, minutes)
        scope.launch { SchoolDepartureReminder.rescheduleAll(context) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            SettingsSwitchRow(
                title = stringResource(R.string.school_departure_setting_title),
                description = stringResource(R.string.school_departure_setting_description),
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    apply(checked, minutesText.toIntOrNull() ?: SchoolDepartureReminder.minutesBefore(context))
                }
            )
            if (enabled) {
                NumberSettingRow(
                    label = stringResource(R.string.school_departure_minutes_label),
                    value = minutesText,
                    unit = stringResource(R.string.school_departure_minutes_unit),
                    onValueChange = { input ->
                        minutesText = input
                        input.toIntOrNull()
                            ?.takeIf { it in SCHOOL_DEPARTURE_MINUTES_RANGE }
                            ?.let { apply(true, it) }
                    }
                )
                if (!context.canPostAppNotifications()) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Text(
                            stringResource(R.string.school_departure_notifications_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.school_departure_open_notification_settings))
                        }
                    }
                }
            }
        }
    }
}
