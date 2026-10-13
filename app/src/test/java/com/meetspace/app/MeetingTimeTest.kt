package com.meetspace.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class MeetingTimeTest {
    @Test fun dstMonthEndsAtPostgresMicrosecondBeforeNextLocalMonth() {
        val (from, to) = MeetingTime.calendarRange(YearMonth.of(2026, 3), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-01T05:00:00Z"), from)
        assertEquals(Instant.parse("2026-04-01T03:59:59.999999Z"), to)
        assertEquals(0, to.nano % 1000)
    }

    @Test fun leapFebruaryUsesLocalDateRatherThanFixedDayCount() {
        val (from, to) = MeetingTime.calendarRange(YearMonth.of(2028, 2), ZoneId.of("Europe/Moscow"))
        assertEquals(Instant.parse("2028-01-31T21:00:00Z"), from)
        assertEquals(Instant.parse("2028-02-29T20:59:59.999999Z"), to)
    }

    @Test fun nonexistentDstTimeIsRejectedInsteadOfShifted() {
        assertThrows(IllegalArgumentException::class.java) {
            MeetingTime.resolve(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), ZoneId.of("America/New_York"))
        }
    }

    @Test fun repeatedDstTimeRequiresOffsetAndBothOffsetsResolveDistinctInstants() {
        val date = LocalDate.of(2026, 11, 1)
        val time = LocalTime.of(1, 30)
        val zone = ZoneId.of("America/New_York")
        assertThrows(IllegalArgumentException::class.java) { MeetingTime.resolve(date, time, zone) }
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), MeetingTime.resolve(date, time, zone, ZoneOffset.ofHours(-4)))
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), MeetingTime.resolve(date, time, zone, ZoneOffset.ofHours(-5)))
        assertThrows(IllegalArgumentException::class.java) { MeetingTime.resolve(date, time, zone, ZoneOffset.UTC) }
    }

    @Test fun ordinaryLocalTimeRejectsIncorrectPreferredOffset() {
        val date = LocalDate.of(2026, 10, 11)
        val time = LocalTime.of(15, 30)
        assertEquals(Instant.parse("2026-10-11T12:30:00Z"), MeetingTime.resolve(date, time, ZoneId.of("Europe/Moscow")))
        assertThrows(IllegalArgumentException::class.java) { MeetingTime.resolve(date, time, ZoneId.of("Europe/Moscow"), ZoneOffset.UTC) }
    }
}
