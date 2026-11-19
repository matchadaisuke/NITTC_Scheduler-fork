package jp.linkserver.nittcsc.data

import jp.linkserver.nittcsc.logic.PeriodLabelStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CustomizationModelsTest {
    @Test
    fun schedulePresets_roundTripAllScheduleFieldsAndKeepFive() {
        val presets = (1..6).map { index ->
            SchedulePreset(
                id = "preset-$index",
                name = "時程 $index",
                periodsPerDay = index + 2,
                periodDurationMin = 40 + index,
                breakBetweenPeriodsMin = 5 + index,
                lunchBreakMin = 45 + index,
                lunchAfterPeriod = 2,
                firstPeriodStartHour = 8,
                firstPeriodStartMinute = index,
                periodLabelStyle = PeriodLabelStyle.SINGLE_KOSHI,
                arrivalHour = 7,
                arrivalMinute = 30 + index,
                departureHour = 16,
                departureMinute = index
            )
        }

        val decoded = decodeSchedulePresets(encodeSchedulePresets(presets))

        assertEquals(presets.take(5), decoded)
    }

    @Test
    fun schedulePreset_fromSettingsIncludesDetailedTimes() {
        val settings = SettingsEntity(
            termStart = LocalDate.of(2026, 4, 1),
            termEnd = LocalDate.of(2027, 3, 31),
            periodsPerDay = 6,
            periodDurationMin = 50,
            breakBetweenPeriodsMin = 15,
            lunchBreakMin = 55,
            lunchAfterPeriod = 3,
            firstPeriodStartHour = 8,
            firstPeriodStartMinute = 50,
            periodLabelStyle = PeriodLabelStyle.SINGLE_KOSHI,
            arrivalHour = 8,
            arrivalMinute = 20,
            departureHour = 17,
            departureMinute = 10
        )

        val preset = SchedulePreset.fromSettings(settings, "normal", "通常時程")

        assertEquals(6, preset.periodsPerDay)
        assertEquals(50, preset.periodDurationMin)
        assertEquals(15, preset.breakBetweenPeriodsMin)
        assertEquals(55, preset.lunchBreakMin)
        assertEquals(3, preset.lunchAfterPeriod)
        assertEquals(8, preset.firstPeriodStartHour)
        assertEquals(50, preset.firstPeriodStartMinute)
        assertEquals(8, preset.arrivalHour)
        assertEquals(20, preset.arrivalMinute)
        assertEquals(17, preset.departureHour)
        assertEquals(10, preset.departureMinute)
    }

    @Test
    fun notificationCustomization_roundTripsFourTriggersAndTemplates() {
        val config = LessonNotificationCustomization(
            startTriggers = listOf(
                LessonNotificationTrigger(true, 20),
                LessonNotificationTrigger(false, 3)
            ),
            endTriggers = listOf(
                LessonNotificationTrigger(true, 15),
                LessonNotificationTrigger(true, 2)
            ),
            startTitleTemplate = "{period} {subject}",
            startBodyTemplate = "{minutes}分前 / 次は{next_subject}",
            endTitleTemplate = "{subject} 終了前",
            endBodyTemplate = "次の教室: {next_location}"
        )

        val decoded = decodeLessonNotificationCustomization(
            encodeLessonNotificationCustomization(config)
        )

        assertEquals(config, decoded)
    }

    @Test
    fun malformedCustomizationJsonFallsBackToSafeDefaults() {
        val config = decodeLessonNotificationCustomization("{broken", legacyStartMinutes = 25)

        assertEquals(2, config.startTriggers.size)
        assertEquals(2, config.endTriggers.size)
        assertTrue(config.startTriggers[0].enabled)
        assertEquals(25, config.startTriggers[0].minutesBefore)
        assertFalse(config.endTriggers[0].enabled)
        assertTrue(config.startBodyTemplate.contains("{next_subject}"))
    }

    @Test
    fun notificationTemplateRendersCurrentAndNextLessonVariables() {
        val rendered = renderLessonNotificationTemplate(
            "{subject} {period} {start_time}-{end_time} / あと{minutes}分 / 次: {next_subject} {next_location} {next_start_time}",
            LessonNotificationTemplateValues(
                subject = "数学",
                period = "2限",
                startTime = "10:30",
                endTime = "11:20",
                minutes = 10,
                nextSubject = "物理",
                nextLocation = "物理実験室",
                nextStartTime = "11:35"
            )
        )

        assertEquals(
            "数学 2限 10:30-11:20 / あと10分 / 次: 物理 物理実験室 11:35",
            rendered
        )
    }

    @Test
    fun arrivalAndDepartureSettingsRoundTrip() {
        val base = LessonNotificationCustomization.defaults()
        val config = base.copy(
            arrivalTriggers = listOf(LessonNotificationTrigger(true, 45), LessonNotificationTrigger(true, 5)),
            departureTriggers = listOf(LessonNotificationTrigger(true, 15), LessonNotificationTrigger(false, 0)),
            arrivalTitleTemplate = "登校 {minutes}",
            departureBodyTemplate = "下校 {time}"
        )
        val decoded = decodeLessonNotificationCustomization(encodeLessonNotificationCustomization(config))
        assertEquals(config.arrivalTriggers, decoded.arrivalTriggers)
        assertEquals(config.departureTriggers, decoded.departureTriggers)
        assertEquals("登校 {minutes}", decoded.arrivalTitleTemplate)
        assertEquals("下校 {time}", decoded.departureBodyTemplate)
    }

    @Test
    fun legacyJsonWithoutTripKeysFallsBackToDefaults() {
        val legacy = """{"startTriggers":[{"enabled":true,"minutesBefore":7},{"enabled":false,"minutesBefore":5}]}"""
        val decoded = decodeLessonNotificationCustomization(legacy)
        assertEquals(7, decoded.startTriggers.first().minutesBefore)
        assertEquals(DEFAULT_ARRIVAL_TRIGGERS, decoded.arrivalTriggers)
        assertEquals(DEFAULT_DEPARTURE_TITLE_TEMPLATE, decoded.departureTitleTemplate)
    }

    @Test
    fun schoolTripTemplateReplacesAllVariables() {
        val text = renderSchoolTripTemplate(
            "{time}の{minutes}分前 {first_period}{first_subject}@{first_location} {first_start_time} 全{lesson_count}コマ 明日:{tomorrow_first_subject}",
            SchoolTripTemplateValues(
                minutes = 10, time = "08:40", firstSubject = "数学", firstLocation = "101",
                firstPeriod = "1限", firstStartTime = "09:05", lessonCount = 4, tomorrowFirstSubject = "英語"
            )
        )
        assertEquals("08:40の10分前 1限数学@101 09:05 全4コマ 明日:英語", text)
    }
}
