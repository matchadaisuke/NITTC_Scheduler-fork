package jp.linkserver.nittcsc.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SchoolTripReminderPolicyTest {
    private val date = LocalDate.of(2026, 11, 17)

    @Test
    fun notificationTimeIsMinutesBeforeBaseTime() {
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 7, 40),
            schoolTripNotificationTime(date, LocalTime.of(8, 40), 60)
        )
        // 0分前（ちょうど）も許容する。
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 15, 45),
            schoolTripNotificationTime(date, LocalTime.of(15, 45), 0)
        )
    }

    @Test
    fun minutesAreClampedToAllowedRange() {
        assertEquals(0, normalizeSchoolTripMinutes(-5))
        assertEquals(360, normalizeSchoolTripMinutes(10_000))
        assertEquals(60, normalizeSchoolTripMinutes(60))
    }

    @Test
    fun deliveryDecisionCoversEarlyOnTimeAndLate() {
        val at = LocalDateTime.of(2026, 11, 17, 7, 40)
        val base = LocalDateTime.of(2026, 11, 17, 8, 40)
        assertEquals(SchoolTripDelivery.TOO_EARLY, schoolTripDeliveryDecision(at.minusMinutes(10), at, base))
        assertEquals(SchoolTripDelivery.DELIVER, schoolTripDeliveryDecision(at.minusMinutes(1), at, base))
        assertEquals(SchoolTripDelivery.DELIVER, schoolTripDeliveryDecision(at.plusMinutes(20), at, base))
        assertEquals(SchoolTripDelivery.TOO_LATE, schoolTripDeliveryDecision(base.plusMinutes(1), at, base))
    }
}
