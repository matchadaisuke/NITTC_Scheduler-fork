package jp.linkserver.nittcsc.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jp.linkserver.nittcsc.R
import jp.linkserver.nittcsc.data.LessonNotificationCustomization
import jp.linkserver.nittcsc.data.LessonNotificationTrigger
import jp.linkserver.nittcsc.data.SettingsEntity
import jp.linkserver.nittcsc.data.lessonNotificationCustomization
import jp.linkserver.nittcsc.reminder.canPostAppNotifications
import jp.linkserver.nittcsc.ui.components.AppSettingsCategory
import jp.linkserver.nittcsc.ui.components.AppSettingsGroup
import jp.linkserver.nittcsc.ui.components.AppSettingsScaffold
import jp.linkserver.nittcsc.ui.components.NavigationPreferenceRow
import jp.linkserver.nittcsc.ui.theme.LocalUiDesignMode
import jp.linkserver.nittcsc.data.UiDesignMode

/** 通知設定の個別ページ。「通知」一覧から1つずつ開く。 */
enum class NotificationSettingsPage(val titleRes: Int) {
    LESSON_START(R.string.notification_page_lesson_start),
    LESSON_END(R.string.notification_page_lesson_end),
    ARRIVAL(R.string.notification_page_arrival),
    DEPARTURE(R.string.notification_page_departure),
    TASKS(R.string.notification_page_tasks),
    PLANS(R.string.notification_page_plans)
}

/** 設定画面に置く「通知」一覧。各行に現在のオン/オフとタイミングを要約して出す。 */
@Composable
internal fun NotificationSettingsList(
    settings: SettingsEntity?,
    lessonStartEnabled: Boolean,
    onOpen: (NotificationSettingsPage) -> Unit
) {
    val config = settings?.lessonNotificationCustomization()
    AppSettingsCategory(title = stringResource(R.string.section_notification_settings))
    AppSettingsGroup {
        NotificationSettingsPage.entries.forEach { page ->
            item("notification_page_${page.name}") {
                val summary = when (page) {
                    NotificationSettingsPage.LESSON_START ->
                        if (lessonStartEnabled) triggerSummary(config?.startTriggers) else stringResource(R.string.notification_summary_off)
                    NotificationSettingsPage.LESSON_END -> triggerSummary(config?.endTriggers)
                    NotificationSettingsPage.ARRIVAL -> triggerSummary(config?.arrivalTriggers)
                    NotificationSettingsPage.DEPARTURE -> triggerSummary(config?.departureTriggers)
                    NotificationSettingsPage.TASKS -> stringResource(R.string.notification_summary_tasks)
                    NotificationSettingsPage.PLANS -> stringResource(R.string.notification_summary_plans)
                }
                NavigationPreferenceRow(stringResource(page.titleRes), summary) { onOpen(page) }
            }
        }
    }
}

@Composable
private fun triggerSummary(triggers: List<LessonNotificationTrigger>?): String {
    val enabled = triggers.orEmpty().filter { it.enabled }
    if (enabled.isEmpty()) return stringResource(R.string.notification_summary_off)
    val timings = enabled.joinToString("・") {
        if (it.minutesBefore == 0) stringResource(R.string.notification_summary_at_time)
        else stringResource(R.string.notification_summary_minutes_before, it.minutesBefore)
    }
    return stringResource(R.string.notification_summary_timings, timings)
}

/**
 * 個別ページ本体。[lessonStartDetails] は授業開始前の既存の詳細設定（ライブ更新・通知しない授業など）を差し込む枠。
 */
@Composable
internal fun NotificationSettingsPageScreen(
    page: NotificationSettingsPage,
    settings: SettingsEntity?,
    onBack: () -> Unit,
    onUpdate: (LessonNotificationCustomization) -> Unit,
    lessonStartDetails: @Composable () -> Unit
) {
    // 通常M3は遷移ホストを持たない。M3EのBackは呼び出し側の遷移ホストに集約する。
    if (LocalUiDesignMode.current == UiDesignMode.MATERIAL_3) BackHandler(onBack = onBack)
    val context = LocalContext.current
    AppSettingsScaffold(
        title = stringResource(page.titleRes),
        onBack = onBack,
        scrollState = rememberScrollState()
    ) {
        if (!context.canPostAppNotifications()) NotificationBlockedNotice()
        val config = settings?.lessonNotificationCustomization()
        when (page) {
            NotificationSettingsPage.LESSON_START -> {
                lessonStartDetails()
                if (config != null) {
                    TimingSection(config.startTriggers, { i -> stringResource(R.string.label_lesson_notification_start_child, i) }) {
                        onUpdate(config.copy(startTriggers = it))
                    }
                    TemplateSection(
                        titleLabel = stringResource(R.string.notification_template_title_label),
                        title = config.startTitleTemplate,
                        onTitle = { onUpdate(config.copy(startTitleTemplate = it)) },
                        bodyLabel = stringResource(R.string.notification_template_body_label),
                        body = config.startBodyTemplate,
                        onBody = { onUpdate(config.copy(startBodyTemplate = it)) },
                        variables = lessonTemplateVariables()
                    )
                }
            }
            NotificationSettingsPage.LESSON_END -> if (config != null) {
                TimingSection(config.endTriggers, { i -> stringResource(R.string.label_lesson_notification_end_child, i) }) {
                    onUpdate(config.copy(endTriggers = it))
                }
                TemplateSection(
                    titleLabel = stringResource(R.string.notification_template_title_label),
                    title = config.endTitleTemplate,
                    onTitle = { onUpdate(config.copy(endTitleTemplate = it)) },
                    bodyLabel = stringResource(R.string.notification_template_body_label),
                    body = config.endBodyTemplate,
                    onBody = { onUpdate(config.copy(endBodyTemplate = it)) },
                    variables = lessonTemplateVariables()
                )
            }
            NotificationSettingsPage.ARRIVAL -> if (config != null) {
                Description(stringResource(R.string.notification_arrival_description))
                TimingSection(config.arrivalTriggers, { i -> stringResource(R.string.notification_timing_item, i) }) {
                    onUpdate(config.copy(arrivalTriggers = it))
                }
                TemplateSection(
                    titleLabel = stringResource(R.string.notification_template_title_label),
                    title = config.arrivalTitleTemplate,
                    onTitle = { onUpdate(config.copy(arrivalTitleTemplate = it)) },
                    bodyLabel = stringResource(R.string.notification_template_body_label),
                    body = config.arrivalBodyTemplate,
                    onBody = { onUpdate(config.copy(arrivalBodyTemplate = it)) },
                    variables = tripTemplateVariables(arrival = true)
                )
            }
            NotificationSettingsPage.DEPARTURE -> if (config != null) {
                Description(stringResource(R.string.notification_departure_description))
                TimingSection(config.departureTriggers, { i -> stringResource(R.string.notification_timing_item, i) }) {
                    onUpdate(config.copy(departureTriggers = it))
                }
                TemplateSection(
                    titleLabel = stringResource(R.string.notification_template_title_label),
                    title = config.departureTitleTemplate,
                    onTitle = { onUpdate(config.copy(departureTitleTemplate = it)) },
                    bodyLabel = stringResource(R.string.notification_template_body_label),
                    body = config.departureBodyTemplate,
                    onBody = { onUpdate(config.copy(departureBodyTemplate = it)) },
                    variables = tripTemplateVariables(arrival = false)
                )
            }
            NotificationSettingsPage.TASKS -> Description(stringResource(R.string.notification_tasks_description))
            NotificationSettingsPage.PLANS -> Description(stringResource(R.string.notification_plans_description))
        }
        OpenSystemNotificationSettings()
    }
}

@Composable
private fun ColumnScope.Description(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/** タイミング（最大2つ）。他の設定と同じ余白のグループ内に、1行ずつ「名前・何分前・オン/オフ」を並べる。 */
@Composable
private fun ColumnScope.TimingSection(
    triggers: List<LessonNotificationTrigger>,
    itemTitle: @Composable (Int) -> String,
    onChange: (List<LessonNotificationTrigger>) -> Unit
) {
    AppSettingsCategory(title = stringResource(R.string.notification_timing_title))
    AppSettingsGroup {
        triggers.take(2).forEachIndexed { index, trigger ->
            item("timing_$index", contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                NotificationTriggerEditor(
                    title = itemTitle(index + 1),
                    trigger = trigger,
                    onChange = { changed -> onChange(triggers.toMutableList().also { it[index] = changed }) }
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.TemplateSection(
    titleLabel: String,
    title: String,
    onTitle: (String) -> Unit,
    bodyLabel: String,
    body: String,
    onBody: (String) -> Unit,
    variables: List<TemplateVariable>
) {
    AppSettingsCategory(title = stringResource(R.string.notification_template_title))
    AppSettingsGroup {
        item("template_fields", contentPadding = PaddingValues(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TemplateField(label = titleLabel, value = title, variables = variables, onChange = onTitle)
                TemplateField(label = bodyLabel, value = body, multiline = true, variables = variables, onChange = onBody)
            }
        }
    }
}

@Composable
private fun ColumnScope.NotificationBlockedNotice() {
    Text(
        text = stringResource(R.string.notification_blocked_warning),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ColumnScope.OpenSystemNotificationSettings() {
    val context = LocalContext.current
    Button(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(stringResource(R.string.notification_open_system_settings))
    }
}
