package com.example.railtime.controller;

import com.example.railtime.config.ApiPaths;
import com.example.railtime.config.OpenApiConfig;
import com.example.railtime.model.request.CancellationRequest;
import com.example.railtime.model.request.ChartRequest;
import com.example.railtime.model.response.BookingWindowResponse;
import com.example.railtime.model.response.CancellationResponse;
import com.example.railtime.model.response.ChartResponse;
import com.example.railtime.model.response.ErrorResponse;
import com.example.railtime.service.BookingWindowService;
import com.example.railtime.service.CancellationRefundService;
import com.example.railtime.service.ChartPreparationService;
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

/** Journey-planning tools: cancellation refund, chart timing and today's booking windows. */
@RestController
@RequestMapping(path = ApiPaths.BOOKING, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = OpenApiConfig.TAG_PLANNING)
public class PlanningController {

    private final CancellationRefundService cancellationService;
    private final ChartPreparationService chartService;
    private final BookingWindowService windowService;

    public PlanningController(CancellationRefundService cancellationService, ChartPreparationService chartService,
                              BookingWindowService windowService) {
        this.cancellationService = cancellationService;
        this.chartService = chartService;
        this.windowService = windowService;
    }

    @Operation(summary = "Estimate the refund on cancellation",
            description = "Railway Passengers (Cancellation of Ticket and Refund of Fare) Rules, 2015, as amended in "
                    + "2026. Standard trains (cancelled from 1 Apr 2026, ruleSet STANDARD_2026): confirmed tickets pay "
                    + "a flat charge per class (>72 h), 25% (72–24 h) or 50% (24–8 h) of fare with that minimum, and "
                    + "get no refund within 8 h. Earlier cancellations use the original 48/12/4 h slabs "
                    + "(STANDARD_2015). trainType VANDE_BHARAT_SLEEPER or AMRIT_BHARAT_II (PREMIUM_SLEEPER_2026): 25% "
                    + "of fare above 72 h, 50% from 72 to 8 h, then no refund; Vande Bharat Sleeper has no RAC. On "
                    + "every train confirmed Tatkal gets no refund and RAC / waitlisted pay clerkage up to 30 minutes "
                    + "before departure. GST is added on the charge for AC classes and FC. Cancellation time "
                    + "defaults to now (IST).")
    @ApiResponse(responseCode = "200", description = "Estimate",
            content = @Content(schema = @Schema(implementation = CancellationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing/invalid fields, cancellation after departure, "
            + "or RAC on a Vande Bharat Sleeper train",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(path = ApiPaths.CANCELLATION_CALCULATE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CancellationResponse> cancellation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
                    {"travelClass":"3A","ticketStatus":"CONFIRMED","totalFare":2450,"passengers":2,
                     "departureDate":"2026-12-20","departureTime":"18:30","cancellationDate":"2026-12-18","cancellationTime":"10:00"}""")))
            @Valid @RequestBody CancellationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cancellationService.calculate(request));
    }

    @Operation(summary = "Estimate reservation chart times",
            description = "Railway Board letter of 16 December 2025: departures from 05:01 to 14:00 (inclusive) get "
                    + "the first chart by 20:00 the previous day; other departures 10 hours before departure. The "
                    + "final chart is prepared 30 minutes before departure.")
    @ApiResponse(responseCode = "200", description = "Estimate",
            content = @Content(schema = @Schema(implementation = ChartResponse.class)))
    @ApiResponse(responseCode = "400", description = "Missing/invalid date or time",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(path = ApiPaths.CHART_CALCULATE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChartResponse> chart(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples =
                    @ExampleObject(value = "{\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:30\"}")))
            @Valid @RequestBody ChartRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chartService.calculate(request));
    }

    @Operation(summary = "How far ahead can I book today?",
            description = "The furthest journey date already open for general reservation, the next date to open "
                    + "and when, and today's Tatkal windows (for journeys departing tomorrow).")
    @ApiResponse(responseCode = "200", description = "Booking windows as of now")
    @GetMapping(ApiPaths.WINDOW)
    public ResponseEntity<BookingWindowResponse> window() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(windowService.today());
    }
}
