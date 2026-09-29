package com.example.railtime.service;

import com.example.railtime.model.response.BookingWindowResponse;
import com.example.railtime.properties.BookingRulesProperties;
import com.example.railtime.util.DateTimeUtil;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;

/**
 * "How far ahead can I book today?" — the reverse of the general reservation calculator.
 *
 * <p>Journey date {@code J} opens at the opening time on {@code J − ARP}. So once today's opening time
 * has passed, journeys up to {@code today + ARP} are open; before it, only up to {@code today + ARP − 1}.
 * Today's Tatkal windows are for journeys departing {@code today + daysBeforeDeparture}.
 */
@Service
public class BookingWindowService {

    private final BookingRuleService rules;
    private final BookingRulesProperties properties;

    public BookingWindowService(BookingRuleService rules, BookingRulesProperties properties) {
        this.rules = rules;
        this.properties = properties;
    }

    public BookingWindowResponse today() {
        ZonedDateTime now = rules.now();
        LocalDate today = now.toLocalDate();
        int arp = rules.advanceReservationDays();
        boolean openedToday = !now.toLocalTime().isBefore(properties.general().openingTime());

        LocalDate latestOpen = today.plusDays(openedToday ? arp : arp - 1L);
        LocalDate nextOpeningDay = openedToday ? today.plusDays(1) : today;
        ZonedDateTime nextOpensAt = ZonedDateTime.of(nextOpeningDay, properties.general().openingTime(), rules.zone());
        LocalDate nextJourney = nextOpeningDay.plusDays(arp);

        BookingRulesProperties.Tatkal tatkal = properties.tatkal();
        ZonedDateTime acOpens = ZonedDateTime.of(today, tatkal.acOpeningTime(), rules.zone());
        ZonedDateTime nonAcOpens = ZonedDateTime.of(today, tatkal.nonAcOpeningTime(), rules.zone());
        LocalDate tatkalJourney = today.plusDays(tatkal.daysBeforeDeparture());

        return new BookingWindowResponse(
                now.toOffsetDateTime(),
                latestOpen,
                DateTimeUtil.formatLongDate(latestOpen),
                new BookingWindowResponse.NextOpening(
                        nextJourney,
                        DateTimeUtil.formatLongDate(nextJourney),
                        nextOpensAt.toOffsetDateTime(),
                        (openedToday ? "Tomorrow" : "Today") + ", " + DateTimeUtil.formatTimeWithZone(nextOpensAt),
                        DateTimeUtil.millisUntil(now, nextOpensAt)),
                new BookingWindowResponse.TatkalToday(
                        tatkalJourney,
                        DateTimeUtil.formatLongDate(tatkalJourney),
                        acOpens.toOffsetDateTime(),
                        !now.isBefore(acOpens),
                        nonAcOpens.toOffsetDateTime(),
                        !now.isBefore(nonAcOpens),
                        DateTimeUtil.formatTime(tatkal.acOpeningTime()),
                        DateTimeUtil.formatTime(tatkal.nonAcOpeningTime())));
    }
}
