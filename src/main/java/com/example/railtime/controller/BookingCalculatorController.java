package com.example.railtime.controller;

import com.example.railtime.config.ApiPaths;
import com.example.railtime.config.OpenApiConfig;
import com.example.railtime.model.request.ReservationCalculationRequest;
import com.example.railtime.model.request.TatkalCalculationRequest;
import com.example.railtime.model.response.BookingRulesResponse;
import com.example.railtime.model.response.ErrorResponse;
import com.example.railtime.model.response.ReservationCalculationResponse;
import com.example.railtime.model.response.TatkalCalculationResponse;
import com.example.railtime.properties.ApplicationProperties;
import com.example.railtime.service.BookingRuleService;
import com.example.railtime.service.ReservationCalculatorService;
import com.example.railtime.service.TatkalCalculatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for the booking calculators. Thin by design: all rules live in the service layer. */
@RestController
@RequestMapping(path = ApiPaths.BOOKING, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = OpenApiConfig.TAG_BOOKING)
public class BookingCalculatorController {

    private static final String EX_VALIDATION = """
            {"timestamp":"2026-09-28T21:30:00+05:30","status":400,"error":"VALIDATION_ERROR",
             "message":"Origin departure date is required","path":"/api/v1/booking/general/calculate",
             "details":[{"field":"originDepartureDate","message":"Origin departure date is required"}]}""";
    private static final String EX_BAD_DATE = """
            {"timestamp":"2026-09-28T21:30:00+05:30","status":400,"error":"INVALID_DATE",
             "message":"Dates must be valid calendar dates in ISO-8601 format (yyyy-MM-dd)",
             "path":"/api/v1/booking/general/calculate"}""";
    private static final String EX_BAD_CLASS = """
            {"timestamp":"2026-09-28T21:30:00+05:30","status":400,"error":"INVALID_TRAVEL_CLASS",
             "message":"Invalid travel class 'XY'. Allowed values: SL, 1A, 2A, 3A, CC, EC, 3E, FC, 2S",
             "path":"/api/v1/booking/tatkal/calculate"}""";
    private static final String EX_GENERAL = """
            {"type":"GENERAL_RESERVATION","journeyDate":"2026-12-20","bookingDate":"2026-10-21",
             "bookingTime":"08:00:00","timeZone":"Asia/Kolkata","bookingDateTime":"2026-10-21T08:00:00+05:30",
             "arpDays":60,"status":"NOT_OPEN","millisUntilOpening":1987200000,
             "calculatedAt":"2026-09-28T08:00:00+05:30",
             "display":{"bookingDate":"21 October 2026","bookingWeekday":"Wednesday",
                        "bookingTime":"08:00 AM IST","journeyDate":"20 December 2026"},
             "notes":["Advance Reservation Period (ARP): 60 days, excluding the date of journey."]}""";
    private static final String EX_TATKAL = """
            {"type":"TATKAL","originDepartureDate":"2026-12-20","travelClass":"SL","travelClassName":"Sleeper",
             "tatkalCategory":"NON_AC","bookingDate":"2026-12-19","bookingTime":"11:00:00",
             "timeZone":"Asia/Kolkata","bookingDateTime":"2026-12-19T11:00:00+05:30","status":"NOT_OPEN",
             "millisUntilOpening":7095600000,"calculatedAt":"2026-09-28T08:00:00+05:30",
             "display":{"bookingDate":"19 December 2026","bookingWeekday":"Saturday",
                        "bookingTime":"11:00 AM IST","journeyDate":"20 December 2026"},
             "notes":["Tatkal opens 1 day before the origin departure date (journey date excluded)."]}""";
    private static final String EX_TATKAL_1A = """
            {"type":"TATKAL","originDepartureDate":"2026-12-20","travelClass":"1A","travelClassName":"First AC",
             "tatkalCategory":"NOT_LISTED","timeZone":"Asia/Kolkata","status":"NOT_APPLICABLE",
             "calculatedAt":"2026-09-28T08:00:00+05:30",
             "message":"1A Tatkal availability is not listed in the standard IRCTC Tatkal classes. Please verify availability for the specific train.",
             "notes":["Standard Tatkal classes: AC — 2A, 3A, CC, EC, 3E; non-AC — SL, FC, 2S."]}""";

    private final ReservationCalculatorService reservationService;
    private final TatkalCalculatorService tatkalService;
    private final BookingRuleService ruleService;
    private final CacheControl rulesCacheControl;

    public BookingCalculatorController(ReservationCalculatorService reservationService,
                                       TatkalCalculatorService tatkalService,
                                       BookingRuleService ruleService,
                                       ApplicationProperties properties) {
        this.reservationService = reservationService;
        this.tatkalService = tatkalService;
        this.ruleService = ruleService;
        this.rulesCacheControl = CacheControl.maxAge(properties.rulesCacheMaxAge()).cachePublic();
    }

    @Operation(summary = "Calculate general reservation opening",
            description = "Opening date = origin departure date − ARP days (journey date excluded), at 08:00 IST. "
                    + "Past journey dates within the accepted window return status JOURNEY_DATE_PASSED.")
    @ApiResponse(responseCode = "200", description = "Calculated",
            content = @Content(schema = @Schema(implementation = ReservationCalculationResponse.class),
                    examples = @ExampleObject(name = "20 Dec 2026", value = EX_GENERAL)))
    @ApiResponse(responseCode = "400", description = "Missing/invalid date, malformed JSON, or date outside the accepted window",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = {
                    @ExampleObject(name = "Missing date", value = EX_VALIDATION),
                    @ExampleObject(name = "Invalid date", value = EX_BAD_DATE)}))
    @ApiResponse(responseCode = "415", description = "Content-Type is not application/json",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(path = ApiPaths.GENERAL_CALCULATE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservationCalculationResponse> calculateGeneral(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples =
                    @ExampleObject(value = "{\"originDepartureDate\":\"2026-12-20\"}")))
            @Valid @RequestBody ReservationCalculationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reservationService.calculate(request));
    }

    @Operation(summary = "Calculate Tatkal opening",
            description = "Opening date = origin departure date − 1 day. AC classes (2A, 3A, CC, EC, 3E) open at "
                    + "10:00 IST; non-AC classes (SL, FC, 2S) at 11:00 IST. For 1A, which is not listed among the "
                    + "standard Tatkal classes, the response has status NOT_APPLICABLE and an explanatory message.")
    @ApiResponse(responseCode = "200", description = "Calculated, or NOT_APPLICABLE for an unlisted class",
            content = @Content(schema = @Schema(implementation = TatkalCalculationResponse.class), examples = {
                    @ExampleObject(name = "Sleeper (SL)", value = EX_TATKAL),
                    @ExampleObject(name = "First AC (1A)", value = EX_TATKAL_1A)}))
    @ApiResponse(responseCode = "400", description = "Missing fields, invalid date or unknown travel class",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = {
                    @ExampleObject(name = "Unknown class", value = EX_BAD_CLASS),
                    @ExampleObject(name = "Invalid date", value = EX_BAD_DATE)}))
    @ApiResponse(responseCode = "415", description = "Content-Type is not application/json",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(path = ApiPaths.TATKAL_CALCULATE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TatkalCalculationResponse> calculateTatkal(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples =
                    @ExampleObject(value = "{\"originDepartureDate\":\"2026-12-20\",\"travelClass\":\"SL\"}")))
            @Valid @RequestBody TatkalCalculationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tatkalService.calculate(request));
    }

    @Operation(summary = "Describe the configured booking rules",
            description = "Static for a given deployment; cacheable and served with an ETag.")
    @ApiResponse(responseCode = "200", description = "Current rules")
    @GetMapping(ApiPaths.RULES)
    public ResponseEntity<BookingRulesResponse> rules() {
        return ResponseEntity.ok().cacheControl(rulesCacheControl).body(ruleService.describeRules());
    }
}
