package com.meetspace.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** Calendar dates are interpreted in the displayed zone, never implicitly as UTC. */
object MeetingTime {
    fun calendarRange(month: YearMonth, zone: ZoneId): Pair<Instant, Instant> =
        month.atDay(1).atStartOfDay(zone).toInstant() to
            // Backend uses inclusive bounds and PostgreSQL timestamp has microsecond precision.
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusNanos(1_000)

    fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId, preferredOffset: ZoneOffset? = null): Instant {
        val local = date.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "Такого местного времени нет из-за перевода часов. Выберите другое время." }
        require(offsets.size == 1 || (preferredOffset != null && preferredOffset in offsets)) {
            "Это время встречается дважды. Выберите смещение UTC: ${offsets.joinToString()}."
        }
        require(preferredOffset == null || preferredOffset in offsets) { "Смещение UTC не соответствует часовому поясу и дате." }
        return local.toInstant(preferredOffset ?: offsets.single())
    }
}
