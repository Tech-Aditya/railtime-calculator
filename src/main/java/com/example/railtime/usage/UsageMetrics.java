package com.example.railtime.usage;

import com.example.railtime.service.BookingRuleService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Service;

/**
 * Anonymous, in-memory usage counters: page visits and successful calculations per calculator,
 * bucketed by day in IST. Nothing about the visitor is stored (no IP, cookie or identifier).
 *
 * <p>The app is deliberately stateless (no database), so counts start when the server starts and
 * reset on restart; {@link UsageResponse#since()} says when counting began. Each running instance
 * counts only its own traffic.
 */
@Service
public class UsageMetrics {

    /** The calculators whose usage is counted. */
    public enum Calculator { GENERAL, TATKAL, REFUND, CHART }

    static final int RETAIN_DAYS = 92;
    static final int CHART_DAYS = 30;

    private final BookingRuleService rules;
    private final ZonedDateTime startedAt;
    private final ConcurrentMap<LocalDate, Day> days = new ConcurrentHashMap<>();

    /** One day's counters; the adders are thread-safe and the map is fixed at construction. */
    private static final class Day {
        final LongAdder visits = new LongAdder();
        final Map<Calculator, LongAdder> calculations = new EnumMap<>(Calculator.class);

        Day() {
            for (Calculator calculator : Calculator.values()) {
                calculations.put(calculator, new LongAdder());
            }
        }

        long calculationsTotal() {
            return calculations.values().stream().mapToLong(LongAdder::sum).sum();
        }
    }

    public UsageMetrics(BookingRuleService rules) {
        this.rules = rules;
        this.startedAt = rules.now();
    }

    public void recordVisit() {
        today().visits.increment();
    }

    public void recordCalculation(Calculator calculator) {
        today().calculations.get(calculator).increment();
    }

    private Day today() {
        LocalDate date = rules.now().toLocalDate();
        Day day = days.get(date);
        if (day == null) {
            day = days.computeIfAbsent(date, d -> new Day());
            prune(date);
        }
        return day;
    }

    /** Drops days that are no longer shown or summed, so memory stays bounded. */
    private void prune(LocalDate today) {
        LocalDate oldest = today.minusDays(RETAIN_DAYS);
        days.keySet().removeIf(date -> date.isBefore(oldest));
    }

    public UsageResponse snapshot() {
        ZonedDateTime now = rules.now();
        LocalDate today = now.toLocalDate();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monthStart = today.withDayOfMonth(1);

        long total = 0;
        long week = 0;
        long month = 0;
        Map<Calculator, Long> byCalculator = new EnumMap<>(Calculator.class);
        for (Calculator calculator : Calculator.values()) {
            byCalculator.put(calculator, 0L);
        }
        for (Map.Entry<LocalDate, Day> entry : days.entrySet()) {
            LocalDate date = entry.getKey();
            long visits = entry.getValue().visits.sum();
            total += visits;
            if (!date.isBefore(weekStart) && !date.isAfter(today)) {
                week += visits;
            }
            if (!date.isBefore(monthStart) && !date.isAfter(today)) {
                month += visits;
            }
            entry.getValue().calculations.forEach((calculator, count) -> byCalculator.merge(calculator, count.sum(), Long::sum));
        }
        Day todayCounts = days.get(today);

        // Days before counting started are unknown, not zero: the series starts on the start day.
        LocalDate first = today.minusDays(CHART_DAYS - 1L);
        if (first.isBefore(startedAt.toLocalDate())) {
            first = startedAt.toLocalDate();
        }
        List<UsageResponse.DailyPoint> daily = new ArrayList<>(CHART_DAYS);
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            Day day = days.get(date);
            daily.add(new UsageResponse.DailyPoint(date, day == null ? 0 : day.visits.sum(),
                    day == null ? 0 : day.calculationsTotal()));
        }

        return new UsageResponse(
                startedAt.toOffsetDateTime(),
                now.toOffsetDateTime(),
                new UsageResponse.Visits(total, todayCounts == null ? 0 : todayCounts.visits.sum(), week, month),
                new UsageResponse.Calculations(
                        byCalculator.values().stream().mapToLong(Long::longValue).sum(),
                        todayCounts == null ? 0 : todayCounts.calculationsTotal(),
                        byCalculator.get(Calculator.GENERAL),
                        byCalculator.get(Calculator.TATKAL),
                        byCalculator.get(Calculator.REFUND),
                        byCalculator.get(Calculator.CHART)),
                List.copyOf(daily));
    }
}
