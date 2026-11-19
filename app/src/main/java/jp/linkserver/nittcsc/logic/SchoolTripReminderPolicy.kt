package jp.linkserver.nittcsc.logic

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 登校前・下校前通知で、1種類あたりに設定できるタイミング数（授業前通知と同じ）。 */
const val SCHOOL_TRIP_MAX_TRIGGERS = 2
val SCHOOL_TRIP_MINUTES_RANGE: IntRange = 0..360

fun normalizeSchoolTripMinutes(minutes: Int): Int =
    minutes.coerceIn(SCHOOL_TRIP_MINUTES_RANGE.first, SCHOOL_TRIP_MINUTES_RANGE.last)

/** 登校時刻または下校時刻 [baseTime] の [minutesBefore] 分前。 */
fun schoolTripNotificationTime(date: LocalDate, baseTime: LocalTime, minutesBefore: Int): LocalDateTime =
    LocalDateTime.of(date, baseTime).minusMinutes(normalizeSchoolTripMinutes(minutesBefore).toLong())

enum class SchoolTripDelivery { DELIVER, TOO_EARLY, TOO_LATE }

/**
 * 配信してよいか。予定時刻の少し前（[earlyToleranceMinutes]）までは許容し、
 * 基準時刻（登校/下校時刻）を過ぎた後は「前通知」ではなくなるため配信しない。
 */
fun schoolTripDeliveryDecision(
    now: LocalDateTime,
    notificationAt: LocalDateTime,
    baseDateTime: LocalDateTime,
    earlyToleranceMinutes: Long = 2L
): SchoolTripDelivery = when {
    now.isAfter(baseDateTime) -> SchoolTripDelivery.TOO_LATE
    Duration.between(now, notificationAt).toMinutes() > earlyToleranceMinutes -> SchoolTripDelivery.TOO_EARLY
    else -> SchoolTripDelivery.DELIVER
}
