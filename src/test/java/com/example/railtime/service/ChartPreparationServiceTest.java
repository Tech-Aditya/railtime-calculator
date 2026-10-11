package com.example.railtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.railtime.TestFixtures;
import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.request.ChartRequest;
import com.example.railtime.model.response.ChartResponse;
import com.example.railtime.model.response.ChartResponse.ChartRule;
import com.example.railtime.model.response.ChartResponse.ChartStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Clock pinned to 28 Sep 2026, 08:00 IST unless stated. */
class ChartPreparationServiceTest {

    private static ChartPreparationService serviceAt(Clock clock) {
        return new ChartPreparationService(new BookingRuleService(TestFixtures.defaultRules(), clock),
                TestFixtures.defaultRules());
    }

    private final ChartPreparationService service = serviceAt(TestFixtures.fixedClock());

    private static ChartRequest request(String date, String time) {
        return new ChartRequest(LocalDate.parse(date), LocalTime.parse(time));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "05:00, HOURS_BEFORE_DEPARTURE",
            "05:01, PREVIOUS_EVENING",
            "10:30, PREVIOUS_EVENING",
            "14:00, PREVIOUS_EVENING",
            "14:01, HOURS_BEFORE_DEPARTURE",
            "04:59, HOURS_BEFORE_DEPARTURE",
            "00:00, HOURS_BEFORE_DEPARTURE",
            "23:59, HOURS_BEFORE_DEPARTURE"
    })
    void departureTimeSelectsTheRule(LocalTime time, ChartRule expected) {
        assertThat(service.ruleFor(time)).isEqualTo(expected);
    }

    @Test
    void morningDepartureGetsFirstChartThePreviousEvening() {
        ChartResponse r = service.calculate(request("2026-12-20", "10:30"));

        assertThat(r.rule()).isEqualTo(ChartRule.PREVIOUS_EVENING);
        assertThat(r.firstChartAt()).hasToString("2026-12-19T20:00+05:30");
        assertThat(r.finalChartAt()).hasToString("2026-12-20T10:00+05:30");
        assertThat(r.departureDateTime()).hasToString("2026-12-20T10:30+05:30");
        assertThat(r.status()).isEqualTo(ChartStatus.PENDING);
        assertThat(r.millisUntilFirstChart()).isPositive();
        assertThat(r.ruleDescription())
                .isEqualTo("Departures between 05:01 AM and 02:00 PM: first chart by 08:00 PM the previous day");
        assertThat(r.display().firstChartDate()).isEqualTo("19 December 2026");
        assertThat(r.display().firstChartWeekday()).isEqualTo("Saturday");
        assertThat(r.display().firstChartTime()).isEqualTo("08:00 PM IST");
        assertThat(r.display().finalChartTime()).isEqualTo("10:00 AM IST");
        assertThat(r.display().departure()).isEqualTo("20 December 2026, 10:30 AM IST");
    }

    @Test
    void otherDeparturesGetFirstChartTenHoursBefore() {
        ChartResponse r = service.calculate(request("2026-12-20", "22:15"));

        assertThat(r.rule()).isEqualTo(ChartRule.HOURS_BEFORE_DEPARTURE);
        assertThat(r.firstChartAt()).hasToString("2026-12-20T12:15+05:30");
        assertThat(r.finalChartAt()).hasToString("2026-12-20T21:45+05:30");
        assertThat(r.ruleDescription()).isEqualTo("First chart 10 hours before departure");
    }

    /** "05:01 to 14:00": 14:00 is in the previous-evening band, 05:00 is not. */
    @Test
    void bandEdgesFollowThePublishedWording() {
        assertThat(service.calculate(request("2026-12-20", "14:00")).firstChartAt()).hasToString("2026-12-19T20:00+05:30");
        assertThat(service.calculate(request("2026-12-20", "05:00")).firstChartAt()).hasToString("2026-12-19T19:00+05:30");
    }

    @Test
    void earlyMorningDepartureCrossesMidnight() {
        ChartResponse r = service.calculate(request("2026-12-20", "02:00"));

        assertThat(r.firstChartAt()).hasToString("2026-12-19T16:00+05:30");
        assertThat(r.finalChartAt()).hasToString("2026-12-20T01:30+05:30");
    }

    @Test
    void pendingCountdownIsExact() {
        // Now 28 Sep 08:00; departure 29 Sep 10:30 → first chart 28 Sep 20:00 → 12 h away.
        ChartResponse r = service.calculate(request("2026-09-29", "10:30"));

        assertThat(r.millisUntilFirstChart()).isEqualTo(12L * 3_600_000);
    }

    @ParameterizedTest(name = "now {0} → {1}")
    @CsvSource({
            "2026-09-27T19:59, PENDING",
            "2026-09-27T20:00, FIRST_CHART_PREPARED",
            "2026-09-28T09:59, FIRST_CHART_PREPARED",
            "2026-09-28T10:00, FINAL_CHART_PREPARED",
            "2026-09-28T10:29, FINAL_CHART_PREPARED",
            "2026-09-28T10:30, DEPARTED"
    })
    void statusFollowsTheClock(LocalDateTime now, ChartStatus expected) {
        ChartResponse r = serviceAt(TestFixtures.clockAtIst(now)).calculate(request("2026-09-28", "10:30"));

        assertThat(r.status()).isEqualTo(expected);
        assertThat(r.millisUntilFirstChart() != null).isEqualTo(expected == ChartStatus.PENDING);
    }

    @Test
    void notesCiteTheChartingRulesAndEndWithVerification() {
        ChartResponse r = service.calculate(request("2026-12-20", "10:30"));

        assertThat(r.notes()).first().isEqualTo("Based on the Railway Board charting rules effective 16 December 2025.");
        assertThat(r.notes()).anyMatch(n -> n.startsWith("The final chart is prepared 30 minutes before departure"));
        assertThat(r.notes()).last().isEqualTo(ChartPreparationService.NOTE_VERIFY);
        assertThat(r.notes()).noneMatch(n -> n.equals(ChartPreparationService.NOTE_BEFORE_RULES));
    }

    @Test
    void flagsDeparturesBeforeTheChartingRules() {
        ChartResponse r = serviceAt(TestFixtures.clockAtIst(LocalDateTime.of(2026, 1, 1, 12, 0)))
                .calculate(request("2025-07-01", "10:30"));

        assertThat(r.status()).isEqualTo(ChartStatus.DEPARTED);
        assertThat(r.notes()).anyMatch(n -> n.equals(ChartPreparationService.NOTE_BEFORE_RULES));
    }

    @Test
    void rejectsDatesOutsideTheSupportedWindow() {
        assertThatThrownBy(() -> service.calculate(request("2030-01-01", "10:00")))
                .isInstanceOf(InvalidDateException.class);
    }
}
