package com.example.railtime.service;

import com.example.railtime.model.request.ChartRequest;
import com.example.railtime.model.response.ChartResponse;
import com.example.railtime.model.response.ChartResponse.ChartRule;
import com.example.railtime.model.response.ChartResponse.ChartStatus;
import com.example.railtime.properties.BookingRulesProperties;
import com.example.railtime.util.DateTimeUtil;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Estimates reservation chart times, per the Railway Board letter of 16 December 2025 (which
 * replaced the July 2025 directive):
 * <ul>
 *   <li>Departures from 05:01 up to and including 14:00: first chart by 20:00 the previous day.</li>
 *   <li>All other departures (14:01 to 05:00): first chart 10 hours before departure.</li>
 *   <li>Final chart: 30 minutes before departure.</li>
 * </ul>
 * Both band limits are inclusive, matching the published "05:01 to 14:00" wording.
 */
@Service
public class ChartPreparationService {

    private static final Logger log = LoggerFactory.getLogger(ChartPreparationService.class);

    static final String NOTE_BEFORE_RULES =
            "This departure is before these timings took effect, when earlier charting times applied.";
    static final String NOTE_VERIFY = "Charting times can be revised by Indian Railways. Check the chart status on IRCTC.";

    private final BookingRuleService rules;
    private final BookingRulesProperties.Chart config;

    public ChartPreparationService(BookingRuleService rules, BookingRulesProperties properties) {
        this.rules = rules;
        this.config = properties.chart();
    }

    public ChartResponse calculate(ChartRequest request) {
        ZonedDateTime now = rules.now();
        rules.validateDateWindow(request.departureDate(), now.toLocalDate(), "Departure date");
        ZonedDateTime departure = ZonedDateTime.of(request.departureDate(), request.departureTime(), rules.zone());

        ChartRule rule = ruleFor(request.departureTime());
        ZonedDateTime firstChart = rule == ChartRule.PREVIOUS_EVENING
                ? ZonedDateTime.of(request.departureDate().minusDays(1), config.previousDayChartTime(), rules.zone())
                : departure.minusHours(config.firstChartHoursBefore());
        ZonedDateTime finalChart = departure.minusMinutes(config.finalChartMinutesBefore());
        ChartStatus status = statusAt(now, firstChart, finalChart, departure);

        log.info("event=calculation type=CHART departure={} rule={} firstChart={} status={}",
                departure.toOffsetDateTime(), rule, firstChart.toOffsetDateTime(), status);

        return new ChartResponse(
                departure.toOffsetDateTime(),
                firstChart.toOffsetDateTime(),
                finalChart.toOffsetDateTime(),
                rule,
                describe(rule),
                status,
                status == ChartStatus.PENDING ? DateTimeUtil.millisUntil(now, firstChart) : null,
                new ChartResponse.Display(
                        DateTimeUtil.formatLongDate(firstChart.toLocalDate()),
                        DateTimeUtil.formatWeekday(firstChart.toLocalDate()),
                        DateTimeUtil.formatTimeWithZone(firstChart),
                        DateTimeUtil.formatTimeWithZone(finalChart),
                        DateTimeUtil.formatLongDate(departure.toLocalDate()) + ", " + DateTimeUtil.formatTimeWithZone(departure)),
                notes(request));
    }

    ChartRule ruleFor(LocalTime departureTime) {
        boolean inPreviousEveningBand = !departureTime.isBefore(config.previousDayBandStart())
                && !departureTime.isAfter(config.previousDayBandEnd());
        return inPreviousEveningBand ? ChartRule.PREVIOUS_EVENING : ChartRule.HOURS_BEFORE_DEPARTURE;
    }

    private static ChartStatus statusAt(ZonedDateTime now, ZonedDateTime firstChart, ZonedDateTime finalChart,
                                        ZonedDateTime departure) {
        if (now.isBefore(firstChart)) {
            return ChartStatus.PENDING;
        }
        if (now.isBefore(finalChart)) {
            return ChartStatus.FIRST_CHART_PREPARED;
        }
        return now.isBefore(departure) ? ChartStatus.FINAL_CHART_PREPARED : ChartStatus.DEPARTED;
    }

    private String describe(ChartRule rule) {
        return rule == ChartRule.PREVIOUS_EVENING
                ? "Departures between %s and %s: first chart by %s the previous day".formatted(
                        DateTimeUtil.formatTime(config.previousDayBandStart()),
                        DateTimeUtil.formatTime(config.previousDayBandEnd()),
                        DateTimeUtil.formatTime(config.previousDayChartTime()))
                : "First chart %d hours before departure".formatted(config.firstChartHoursBefore());
    }

    private List<String> notes(ChartRequest request) {
        List<String> notes = new ArrayList<>(4);
        notes.add("Based on the Railway Board charting rules effective %s."
                .formatted(DateTimeUtil.formatLongDate(config.effectiveFrom())));
        notes.add("The final chart is prepared %d minutes before departure; vacant berths can be booked until then."
                .formatted(config.finalChartMinutesBefore()));
        if (request.departureDate().isBefore(config.effectiveFrom())) {
            notes.add(NOTE_BEFORE_RULES);
        }
        notes.add(NOTE_VERIFY);
        return List.copyOf(notes);
    }
}
