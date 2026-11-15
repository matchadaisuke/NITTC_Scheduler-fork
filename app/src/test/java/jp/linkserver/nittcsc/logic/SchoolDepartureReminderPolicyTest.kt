package jp.linkserver.nittcsc.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SchoolDepartureReminderPolicyTest {
    private val date = LocalDate.of(2026, 11, 17)
    private val start = LocalTime.of(8, 50)

    @Test
    fun notificationTimeIsMinutesBeforeFirstLesson() {
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 7, 50),
            schoolDepartureNotificationTime(date, start, 60)
        )
    }

    @Test
    fun minutesAreClampedToAllowedRange() {
        assertEquals(5, normalizeSchoolDepartureMinutes(0))
        assertEquals(360, normalizeSchoolDepartureMinutes(10_000))
        assertEquals(60, normalizeSchoolDepartureMinutes(60))
    }

    @Test
    fun deliveryDecisionCoversEarlyOnTimeAndLate() {
        val at = LocalDateTime.of(2026, 11, 17, 7, 50)
        val lesson = LocalDateTime.of(2026, 11, 17, 8, 50)
        assertEquals(
            SchoolDepartureDelivery.TOO_EARLY,
            schoolDepartureDeliveryDecision(at.minusMinutes(10), at, lesson)
        )
        assertEquals(
            SchoolDepartureDelivery.DELIVER,
            schoolDepartureDeliveryDecision(at.minusMinutes(1), at, lesson)
        )
        assertEquals(
            SchoolDepartureDelivery.DELIVER,
            schoolDepartureDeliveryDecision(at.plusMinutes(20), at, lesson)
        )
        assertEquals(
            SchoolDepartureDelivery.TOO_LATE,
            schoolDepartureDeliveryDecision(lesson.plusMinutes(1), at, lesson)
        )
    }
}
