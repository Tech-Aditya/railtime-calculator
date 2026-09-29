package com.example.railtime.service;

import com.example.railtime.exception.InvalidDateException;
import com.example.railtime.model.BookingStatus;
import com.example.railtime.model.BookingType;
import com.example.railtime.model.request.ReservationCalculationRequest;
import com.example.railtime.model.response.ReservationCalculationResponse;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Calculates when general (ARP) reservation opens for a train's origin departure date. */
@Service
public class ReservationCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(ReservationCalculatorService.class);

    static final String ORIGIN_DATE_LABEL = "Origin departure date";
    static final String BOARDING_DATE_LABEL = "Boarding date";
    static final String BOARDING_BEFORE_ORIGIN =
            "Boarding date cannot be before the train's origin departure date";

    private final BookingRuleService rules;

    public ReservationCalculatorService(BookingRuleService rules) {
        this.rules = rules;
    }

    public ReservationCalculationResponse calculate(ReservationCalculationRequest request) {
        ZonedDateTime now = rules.now();
        LocalDate journeyDate = request.originDepartureDate();
        LocalDate boardingDate = request.boardingDate();
        validate(journeyDate, boardingDate, now.toLocalDate());

        ZonedDateTime opening = rules.generalOpeningDateTime(journeyDate);
        BookingStatus status = rules.statusOf(opening, journeyDate, now);

        log.info("event=calculation type={} journeyDate={} boardingDateGiven={} bookingDateTime={} status={}",
                BookingType.GENERAL_RESERVATION, journeyDate, boardingDate != null, opening.toOffsetDateTime(), status);

        return new ReservationCalculationResponse(
                BookingType.GENERAL_RESERVATION,
                journeyDate,
                boardingDate,
                opening.toLocalDate(),
                opening.toLocalTime(),
                rules.zone().getId(),
                opening.toOffsetDateTime(),
                rules.advanceReservationDays(),
                status,
                rules.millisUntilOpening(status, now, opening),
                now.toOffsetDateTime(),
                rules.display(opening, journeyDate),
                rules.generalNotes(status, journeyDate, boardingDate));
    }

    private void validate(LocalDate journeyDate, LocalDate boardingDate, LocalDate today) {
        rules.validateDateWindow(journeyDate, today, ORIGIN_DATE_LABEL);
        if (boardingDate == null) {
            return;
        }
        if (boardingDate.isBefore(journeyDate)) {
            throw new InvalidDateException(BOARDING_BEFORE_ORIGIN);
        }
        rules.validateDateWindow(boardingDate, today, BOARDING_DATE_LABEL);
    }
}
