package com.example.railtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.railtime.TestFixtures;
import com.example.railtime.model.response.BookingWindowResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BookingWindowServiceTest {

    private static BookingWindowResponse at(LocalDateTime istNow) {
        BookingRuleService rules = new BookingRuleService(TestFixtures.defaultRules(), TestFixtures.clockAtIst(istNow));
        return new BookingWindowService(rules, TestFixtures.defaultRules()).today();
    }

    @Test
    void afterTodaysOpeningTheFull60DaysAreOpen() {
        BookingWindowResponse r = at(LocalDateTime.of(2026, 9, 28, 8, 0));

        assertThat(r.latestBookableJourneyDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(r.latestBookableJourneyDisplay()).isEqualTo("27 November 2026");
        assertThat(r.nextOpening().journeyDate()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(r.nextOpening().opensAt()).hasToString("2026-09-29T08:00+05:30");
        assertThat(r.nextOpening().opensAtDisplay()).isEqualTo("Tomorrow, 08:00 AM IST");
        assertThat(r.nextOpening().millisUntilOpening()).isEqualTo(24L * 3_600_000);
        assertThat(r.asOf()).hasToString("2026-09-28T08:00+05:30");
    }

    @Test
    void beforeTodaysOpeningOnlyUpTo59DaysAreOpen() {
        BookingWindowResponse r = at(LocalDateTime.of(2026, 9, 28, 7, 30));

        assertThat(r.latestBookableJourneyDate()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(r.nextOpening().journeyDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(r.nextOpening().journeyDisplay()).isEqualTo("27 November 2026");
        assertThat(r.nextOpening().opensAtDisplay()).isEqualTo("Today, 08:00 AM IST");
        assertThat(r.nextOpening().millisUntilOpening()).isEqualTo(30L * 60_000);
    }

    @ParameterizedTest(name = "{0}:{1} → AC {2}, non-AC {3}")
    @CsvSource({
            "9, 59, false, false",
            "10, 0, true, false",
            "10, 59, true, false",
            "11, 0, true, true",
            "23, 59, true, true"
    })
    void tatkalWindowsForTomorrowsJourneys(int hour, int minute, boolean acOpen, boolean nonAcOpen) {
        BookingWindowResponse.TatkalToday t = at(LocalDateTime.of(2026, 9, 28, hour, minute)).tatkal();

        assertThat(t.journeyDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(t.journeyDisplay()).isEqualTo("29 September 2026");
        assertThat(t.acOpen()).isEqualTo(acOpen);
        assertThat(t.nonAcOpen()).isEqualTo(nonAcOpen);
        assertThat(t.acOpensAt()).hasToString("2026-09-28T10:00+05:30");
        assertThat(t.nonAcOpensAt()).hasToString("2026-09-28T11:00+05:30");
        assertThat(t.acTimeDisplay()).isEqualTo("10:00 AM");
        assertThat(t.nonAcTimeDisplay()).isEqualTo("11:00 AM");
    }
}
