package com.example.railtime.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.railtime.TestFixtures;
import com.example.railtime.service.BookingRuleService;
import com.example.railtime.usage.UsageMetrics.Calculator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UsageMetricsTest {

    /** A clock the test can move; clocks derived with withZone() share the same instant. */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MovableClock(LocalDateTime istStart) {
            this(new AtomicReference<>(istStart.atZone(TestFixtures.IST).toInstant()), ZoneOffset.UTC);
        }

        private MovableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void moveTo(LocalDateTime ist) {
            now.set(ist.atZone(TestFixtures.IST).toInstant());
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId newZone) { return new MovableClock(now, newZone); }
        @Override public Instant instant() { return now.get(); }
    }

    // Counting starts on Sunday 27 Sep 2026, 10:00 IST
    private final MovableClock clock = new MovableClock(LocalDateTime.of(2026, 9, 27, 10, 0));
    private final UsageMetrics metrics = new UsageMetrics(new BookingRuleService(TestFixtures.defaultRules(), clock));

    @Test
    void startsEmptyAndDoesNotInventHistoryBeforeTheStart() {
        UsageResponse r = metrics.snapshot();

        assertThat(r.since()).hasToString("2026-09-27T10:00+05:30");
        assertThat(r.visits()).isEqualTo(new UsageResponse.Visits(0, 0, 0, 0));
        assertThat(r.calculations().total()).isZero();
        assertThat(r.daily()).containsExactly(new UsageResponse.DailyPoint(LocalDate.of(2026, 9, 27), 0, 0));
    }

    @Test
    void theDailySeriesGrowsToThirtyDaysThenSlides() {
        clock.moveTo(LocalDateTime.of(2026, 9, 29, 9, 0));
        assertThat(metrics.snapshot().daily()).extracting(UsageResponse.DailyPoint::date)
                .containsExactly(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29));

        clock.moveTo(LocalDateTime.of(2026, 12, 1, 9, 0));
        UsageResponse r = metrics.snapshot();
        assertThat(r.daily()).hasSize(UsageMetrics.CHART_DAYS);
        assertThat(r.daily().getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(r.daily().getLast().date()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void countsVisitsByDayWeekAndMonthInIst() {
        clock.moveTo(LocalDateTime.of(2026, 9, 27, 12, 0)); // Sunday: previous week, same month
        metrics.recordVisit();
        clock.moveTo(LocalDateTime.of(2026, 9, 28, 9, 0)); // Monday: this week
        metrics.recordVisit();
        metrics.recordVisit();
        clock.moveTo(LocalDateTime.of(2026, 10, 1, 0, 10)); // Thursday 1 Oct, just after midnight IST
        metrics.recordVisit();

        UsageResponse r = metrics.snapshot();

        assertThat(r.visits().total()).isEqualTo(4);
        assertThat(r.visits().today()).isEqualTo(1);
        assertThat(r.visits().thisWeek()).isEqualTo(3); // Mon 28 Sep → Thu 1 Oct
        assertThat(r.visits().thisMonth()).isEqualTo(1); // October only
        assertThat(r.daily().getLast()).isEqualTo(new UsageResponse.DailyPoint(LocalDate.of(2026, 10, 1), 1, 0));
        assertThat(r.daily()).contains(new UsageResponse.DailyPoint(LocalDate.of(2026, 9, 28), 2, 0));
    }

    @Test
    void countsCalculationsPerCalculator() {
        metrics.recordCalculation(Calculator.GENERAL);
        metrics.recordCalculation(Calculator.GENERAL);
        metrics.recordCalculation(Calculator.TATKAL);
        metrics.recordCalculation(Calculator.REFUND);
        metrics.recordCalculation(Calculator.CHART);

        UsageResponse.Calculations c = metrics.snapshot().calculations();

        assertThat(c).isEqualTo(new UsageResponse.Calculations(5, 5, 2, 1, 1, 1));
        assertThat(metrics.snapshot().daily().getLast().calculations()).isEqualTo(5);
    }

    @Test
    void forgetsDaysOlderThanTheRetentionWindow() {
        metrics.recordVisit(); // 27 Sep
        clock.moveTo(LocalDateTime.of(2026, 9, 27, 10, 0).plusDays(UsageMetrics.RETAIN_DAYS + 1));
        metrics.recordVisit();

        assertThat(metrics.snapshot().visits().total()).isEqualTo(1);
    }
}
