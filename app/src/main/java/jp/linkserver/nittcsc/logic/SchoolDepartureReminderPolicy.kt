package jp.linkserver.nittcsc.logic

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

const val SCHOOL_DEPARTURE_DEFAULT_MINUTES = 60
val SCHOOL_DEPARTURE_MINUTES_RANGE: IntRange = 5..360

/** 設定値を許容範囲へ丸める。 */
fun normalizeSchoolDepartureMinutes(minutes: Int): Int =
    minutes.coerceIn(SCHOOL_DEPARTURE_MINUTES_RANGE.first, SCHOOL_DEPARTURE_MINUTES_RANGE.last)

/** その日の最初の授業開始の [minutesBefore] 分前。 */
fun schoolDepartureNotificationTime(
    date: LocalDate,
    firstLessonStart: LocalTime,
    minutesBefore: Int
): LocalDateTime =
    LocalDateTime.of(date, firstLessonStart)
        .minusMinutes(normalizeSchoolDepartureMinutes(minutesBefore).toLong())

enum class SchoolDepartureDelivery { DELIVER, TOO_EARLY, TOO_LATE }

/**
 * 配信してよいか。予定時刻の少し前（[earlyToleranceMinutes]）までは許容し、
 * 最初の授業が始まった後は「登校前」ではなくなるため配信しない。
 */
fun schoolDepartureDeliveryDecision(
    now: LocalDateTime,
    notificationAt: LocalDateTime,
    firstLessonStart: LocalDateTime,
    earlyToleranceMinutes: Long = 2L
): SchoolDepartureDelivery = when {
    now.isAfter(firstLessonStart) -> SchoolDepartureDelivery.TOO_LATE
    Duration.between(now, notificationAt).toMinutes() > earlyToleranceMinutes -> SchoolDepartureDelivery.TOO_EARLY
    else -> SchoolDepartureDelivery.DELIVER
}
