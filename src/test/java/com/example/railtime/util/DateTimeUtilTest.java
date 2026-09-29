package com.example.railtime.util;

import static com.example.railtime.TestFixtures.IST;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class DateTimeUtilTest {

    private final ZonedDateTime opening = DateTimeUtil.atZone(LocalDate.of(2026, 10, 21), LocalTime.of(8, 0), IST);

    @Test
    void formatsDisplayValuesInEnglish() {
        assertThat(DateTimeUtil.formatLongDate(LocalDate.of(2026, 10, 21))).isEqualTo("21 October 2026");
        assertThat(DateTimeUtil.formatLongDate(LocalDate.of(2028, 2, 29))).isEqualTo("29 February 2028");
        assertThat(DateTimeUtil.formatWeekday(LocalDate.of(2026, 10, 21))).isEqualTo("Wednesday");
        assertThat(DateTimeUtil.formatTimeWithZone(opening)).isEqualTo("08:00 AM IST");
        assertThat(DateTimeUtil.formatTime(LocalTime.of(11, 0))).isEqualTo("11:00 AM");
    }

    @Test
    void millisUntilIsNeverNegative() {
        assertThat(DateTimeUtil.millisUntil(opening.minusSeconds(5), opening)).isEqualTo(5_000L);
        assertThat(DateTimeUtil.millisUntil(opening, opening)).isZero();
        assertThat(DateTimeUtil.millisUntil(opening.plusDays(1), opening)).isZero();
    }
}
