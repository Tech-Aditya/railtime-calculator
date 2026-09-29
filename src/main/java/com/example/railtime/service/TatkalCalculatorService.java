package com.example.railtime.service;

import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.BookingType;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.request.TatkalCalculationRequest;
import com.example.railtime.model.response.TatkalCalculationResponse;
import com.example.railtime.util.DateTimeUtil;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calculates when Tatkal booking opens for a travel class and origin departure date.
 *
 * <p>Classes not listed under standard IRCTC Tatkal (1A) produce a {@link BookingStatus#NOT_APPLICABLE}
 * business response with an explanatory message and no date/time.
 */
@Service
public class TatkalCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(TatkalCalculatorService.class);

    private final BookingRuleService rules;

    public TatkalCalculatorService(BookingRuleService rules) {
        this.rules = rules;
    }

    public TatkalCalculationResponse calculate(TatkalCalculationRequest request) {
        ZonedDateTime now = rules.now();
        LocalDate originDate = request.originDepartureDate();
        TravelClass travelClass = request.travelClass();
        rules.validateDateWindow(originDate, now.toLocalDate(), ReservationCalculatorService.ORIGIN_DATE_LABEL);

        Optional<LocalTime> openingTime = rules.tatkalOpeningTime(travelClass);
        if (openingTime.isEmpty()) {
            log.info("event=calculation type={} originDate={} travelClass={} status={}",
                    BookingType.TATKAL, originDate, travelClass.code(), BookingStatus.NOT_APPLICABLE);
            return notListed(originDate, travelClass, now);
        }

        ZonedDateTime opening = DateTimeUtil.atZone(rules.tatkalOpeningDate(originDate), openingTime.get(), rules.zone());
        BookingStatus status = rules.statusOf(opening, originDate, now);

        log.info("event=calculation type={} originDate={} travelClass={} bookingDateTime={} status={}",
                BookingType.TATKAL, originDate, travelClass.code(), opening.toOffsetDateTime(), status);

        return new TatkalCalculationResponse(
                BookingType.TATKAL,
                originDate,
                travelClass,
                travelClass.displayName(),
                travelClass.tatkalCategory(),
                opening.toLocalDate(),
                opening.toLocalTime(),
                rules.zone().getId(),
                opening.toOffsetDateTime(),
                status,
                rules.millisUntilOpening(status, now, opening),
                now.toOffsetDateTime(),
                null,
                rules.display(opening, originDate),
                rules.tatkalNotes(travelClass.tatkalCategory(), status));
    }

    private TatkalCalculationResponse notListed(LocalDate originDate, TravelClass travelClass, ZonedDateTime now) {
        return new TatkalCalculationResponse(
                BookingType.TATKAL,
                originDate,
                travelClass,
                travelClass.displayName(),
                travelClass.tatkalCategory(),
                null,
                null,
                rules.zone().getId(),
                null,
                BookingStatus.NOT_APPLICABLE,
                null,
                now.toOffsetDateTime(),
                rules.notListedTatkalMessage(travelClass),
                null,
                rules.tatkalNotes(travelClass.tatkalCategory(), BookingStatus.NOT_APPLICABLE));
    }
}
