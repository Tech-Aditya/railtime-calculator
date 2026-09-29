package com.example.railtime.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.railtime.TestFixtures;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Refund, chart and booking-window endpoints, with the clock pinned to 28 Sep 2026, 08:00 IST. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanningApiIntegrationTest {

    private static final String CANCELLATION = "/api/v1/booking/cancellation/calculate";
    private static final String CHART = "/api/v1/booking/chart/calculate";
    private static final String WINDOW = "/api/v1/booking/window";

    @TestBean
    Clock clock;

    static Clock clock() {
        return TestFixtures.fixedClock();
    }

    @Autowired
    MockMvc mvc;

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void cancellationReturnsTheRefundBreakdown() throws Exception {
        postJson(CANCELLATION, """
                {"travelClass":"3A","ticketStatus":"CONFIRMED","totalFare":2450,"passengers":2,
                 "departureDate":"2026-12-20","departureTime":"18:30","cancellationDate":"2026-12-18","cancellationTime":"10:00"}""")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.travelClass").value("3A"))
                .andExpect(jsonPath("$.travelClassName").value("Third AC"))
                .andExpect(jsonPath("$.slab").value("FLAT_CHARGE"))
                .andExpect(jsonPath("$.refundable").value(true))
                .andExpect(jsonPath("$.minutesBeforeDeparture").value(3390))
                .andExpect(jsonPath("$.totalCharge").value(360.0))
                .andExpect(jsonPath("$.totalGst").value(18.0))
                .andExpect(jsonPath("$.totalRefund").value(2072.0))
                .andExpect(jsonPath("$.departureDateTime").value("2026-12-20T18:30:00+05:30"))
                .andExpect(jsonPath("$.notes[0]").value(containsString("Rules, 2015")));
    }

    @Test
    void cancellationDefaultsToNow() throws Exception {
        postJson(CANCELLATION, """
                {"travelClass":"SL","ticketStatus":"RAC","totalFare":500,"passengers":1,
                 "departureDate":"2026-09-28","departureTime":"20:00"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationDateTime").value("2026-09-28T08:00:00+05:30"))
                .andExpect(jsonPath("$.slab").value("CLERKAGE"))
                .andExpect(jsonPath("$.totalRefund").value(440.0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "missing fare|{\"travelClass\":\"SL\",\"ticketStatus\":\"CONFIRMED\",\"passengers\":1,\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:00\"}|VALIDATION_ERROR|Fare is required",
            "seven passengers|{\"travelClass\":\"SL\",\"ticketStatus\":\"CONFIRMED\",\"totalFare\":100,\"passengers\":7,\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:00\"}|VALIDATION_ERROR|at most 6 passengers",
            "bad status|{\"travelClass\":\"SL\",\"ticketStatus\":\"MAYBE\",\"totalFare\":100,\"passengers\":1,\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:00\"}|VALIDATION_ERROR|Allowed values: CONFIRMED, CONFIRMED_TATKAL, RAC, WAITLISTED",
            "bad time|{\"travelClass\":\"SL\",\"ticketStatus\":\"CONFIRMED\",\"totalFare\":100,\"passengers\":1,\"departureDate\":\"2026-12-20\",\"departureTime\":\"25:00\"}|INVALID_DATE|24-hour times",
            "bad class|{\"travelClass\":\"XX\",\"ticketStatus\":\"CONFIRMED\",\"totalFare\":100,\"passengers\":1,\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:00\"}|INVALID_TRAVEL_CLASS|XX",
            "after departure|{\"travelClass\":\"SL\",\"ticketStatus\":\"CONFIRMED\",\"totalFare\":100,\"passengers\":1,\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:00\",\"cancellationDate\":\"2026-12-20\",\"cancellationTime\":\"10:00\"}|INVALID_DATE|before the scheduled departure",
            "half cancellation time|{\"travelClass\":\"SL\",\"ticketStatus\":\"CONFIRMED\",\"totalFare\":100,\"passengers\":1,\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:00\",\"cancellationDate\":\"2026-12-19\"}|INVALID_DATE|both the cancellation date and time"
    })
    void cancellationRejectsBadInput(String name, String body, String error, String message) throws Exception {
        postJson(CANCELLATION, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.message").value(containsString(message)));
    }

    @Test
    void chartReturnsFirstAndFinalChartTimes() throws Exception {
        postJson(CHART, """
                {"departureDate":"2026-12-20","departureTime":"10:30"}""")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.rule").value("PREVIOUS_EVENING"))
                .andExpect(jsonPath("$.firstChartAt").value("2026-12-19T21:00:00+05:30"))
                .andExpect(jsonPath("$.finalChartAt").value("2026-12-20T10:00:00+05:30"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.display.firstChartTime").value("09:00 PM IST"));
    }

    @Test
    void chartOmitsCountdownOnceTheChartIsOut() throws Exception {
        postJson(CHART, """
                {"departureDate":"2026-09-28","departureTime":"10:30"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FIRST_CHART_PREPARED"))
                .andExpect(jsonPath("$.millisUntilFirstChart").doesNotExist());
    }

    @Test
    void chartRequiresTheDepartureTime() throws Exception {
        postJson(CHART, """
                {"departureDate":"2026-12-20"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void windowDescribesTodaysBookingWindows() throws Exception {
        mvc.perform(get(WINDOW))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.latestBookableJourneyDate").value("2026-11-27"))
                .andExpect(jsonPath("$.nextOpening.journeyDate").value("2026-11-28"))
                .andExpect(jsonPath("$.nextOpening.opensAtDisplay").value("Tomorrow, 08:00 AM IST"))
                .andExpect(jsonPath("$.tatkal.journeyDate").value("2026-09-29"))
                .andExpect(jsonPath("$.tatkal.acOpen").value(false));
    }
}
