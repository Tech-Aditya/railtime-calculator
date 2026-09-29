package com.example.railtime.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.railtime.TestFixtures;
import java.time.Clock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** End-to-end API contract with real services and a clock pinned to 28 Sep 2026, 08:00 IST. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingApiIntegrationTest {

    private static final String GENERAL = "/api/v1/booking/general/calculate";
    private static final String TATKAL = "/api/v1/booking/tatkal/calculate";

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

    @Nested
    class GeneralReservation {

        @Test
        void returnsSpecifiedContract() throws Exception {
            postJson(GENERAL, """
                    {"originDepartureDate":"2026-12-20"}""")
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.type").value("GENERAL_RESERVATION"))
                    .andExpect(jsonPath("$.journeyDate").value("2026-12-20"))
                    .andExpect(jsonPath("$.bookingDate").value("2026-10-21"))
                    .andExpect(jsonPath("$.bookingTime").value("08:00:00"))
                    .andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"))
                    .andExpect(jsonPath("$.bookingDateTime").value("2026-10-21T08:00:00+05:30"))
                    .andExpect(jsonPath("$.arpDays").value(60))
                    .andExpect(jsonPath("$.status").value("NOT_OPEN"))
                    .andExpect(jsonPath("$.millisUntilOpening").value(1_987_200_000L))
                    .andExpect(jsonPath("$.display.bookingDate").value("21 October 2026"))
                    .andExpect(jsonPath("$.display.bookingTime").value("08:00 AM IST"))
                    .andExpect(jsonPath("$.boardingDate").doesNotExist());
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "2027-01-01, 2026-11-02",
                "2027-02-28, 2026-12-30",
                "2028-02-29, 2027-12-31",
                "2026-12-31, 2026-11-01"
        })
        void boundaryDates(String journey, String expectedOpening) throws Exception {
            postJson(GENERAL, "{\"originDepartureDate\":\"" + journey + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingDate").value(expectedOpening))
                    .andExpect(jsonPath("$.bookingDateTime").value(expectedOpening + "T08:00:00+05:30"));
        }

        @Test
        void pastJourneyDate() throws Exception {
            postJson(GENERAL, """
                    {"originDepartureDate":"2026-09-01"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("JOURNEY_DATE_PASSED"))
                    .andExpect(jsonPath("$.millisUntilOpening").doesNotExist());
        }

        @Test
        void acceptsOptionalBoardingDate() throws Exception {
            postJson(GENERAL, """
                    {"originDepartureDate":"2026-12-20","boardingDate":"2026-12-21"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingDate").value("2026-10-21"))
                    .andExpect(jsonPath("$.boardingDate").value("2026-12-21"));
        }

        @Test
        void missingDate() throws Exception {
            postJson(GENERAL, "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("Origin departure date is required"))
                    .andExpect(jsonPath("$.path").value(GENERAL))
                    .andExpect(jsonPath("$.timestamp").value("2026-09-28T08:00:00+05:30"))
                    .andExpect(jsonPath("$.details[0].field").value("originDepartureDate"))
                    .andExpect(jsonPath("$.trace").doesNotExist());
        }

        @ParameterizedTest
        @ValueSource(strings = {"2026-02-30", "20-12-2026", "2026-13-01", "tomorrow", ""})
        void invalidDate(String value) throws Exception {
            postJson(GENERAL, "{\"originDepartureDate\":\"" + value + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(value.isEmpty() ? "VALIDATION_ERROR" : "INVALID_DATE"));
        }

        @Test
        void dateOutsideAcceptedWindow() throws Exception {
            postJson(GENERAL, """
                    {"originDepartureDate":"2031-01-01"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_DATE"))
                    .andExpect(jsonPath("$.message").value("Origin departure date cannot be more than 730 days in the future"));
        }

        @Test
        void boardingBeforeOrigin() throws Exception {
            postJson(GENERAL, """
                    {"originDepartureDate":"2026-12-20","boardingDate":"2026-12-19"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_DATE"));
        }
    }

    @Nested
    class Tatkal {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "SL, 11:00:00", "FC, 11:00:00", "2S, 11:00:00",
                "2A, 10:00:00", "3A, 10:00:00", "CC, 10:00:00", "EC, 10:00:00", "3E, 10:00:00",
                "sl, 11:00:00"
        })
        void opensAtClassTime(String travelClass, String expectedTime) throws Exception {
            postJson(TATKAL, "{\"originDepartureDate\":\"2026-12-20\",\"travelClass\":\"" + travelClass + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("TATKAL"))
                    .andExpect(jsonPath("$.originDepartureDate").value("2026-12-20"))
                    .andExpect(jsonPath("$.travelClass").value(travelClass.toUpperCase()))
                    .andExpect(jsonPath("$.bookingDate").value("2026-12-19"))
                    .andExpect(jsonPath("$.bookingTime").value(expectedTime))
                    .andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"))
                    .andExpect(jsonPath("$.bookingDateTime").value("2026-12-19T" + expectedTime + "+05:30"))
                    .andExpect(jsonPath("$.status").value("NOT_OPEN"));
        }

        @Test
        void firstAcReturnsBusinessResponseNotATime() throws Exception {
            postJson(TATKAL, """
                    {"originDepartureDate":"2026-12-20","travelClass":"1A"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.travelClass").value("1A"))
                    .andExpect(jsonPath("$.status").value("NOT_APPLICABLE"))
                    .andExpect(jsonPath("$.tatkalCategory").value("NOT_LISTED"))
                    .andExpect(jsonPath("$.message").value(startsWith("1A Tatkal availability is not listed")))
                    .andExpect(jsonPath("$.bookingDate").doesNotExist())
                    .andExpect(jsonPath("$.bookingTime").doesNotExist())
                    .andExpect(jsonPath("$.bookingDateTime").doesNotExist());
        }

        @Test
        void invalidTravelClass() throws Exception {
            postJson(TATKAL, """
                    {"originDepartureDate":"2026-12-20","travelClass":"XY"}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TRAVEL_CLASS"))
                    .andExpect(jsonPath("$.message").value(containsString("Allowed values: SL, 1A, 2A, 3A, CC, EC, 3E, FC, 2S")));
        }

        @Test
        void missingFieldsAreAllReported() throws Exception {
            postJson(TATKAL, "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details.length()").value(2))
                    .andExpect(jsonPath("$.details[1].message").value("Travel class is required"));
        }
    }

    @Nested
    class Protocol {

        @Test
        void malformedJsonSaysWhere() throws Exception {
            postJson(GENERAL, "{\"originDepartureDate\": 2026-12}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value(startsWith("Request body is not valid JSON (problem at line 1, column")))
                    .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{8}")));
        }

        @Test
        void truncatedJson() throws Exception {
            postJson(GENERAL, "{\"originDepartureDate\":")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value(startsWith("Request body is not valid JSON")));
        }

        @Test
        void missingBody() throws Exception {
            mvc.perform(post(GENERAL).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Request body is missing. Send a JSON object with the required fields."));
        }

        @Test
        void bodyThatIsNotAnObject() throws Exception {
            postJson(GENERAL, "[\"2026-12-20\"]")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value(startsWith("Request body must be a JSON object")));
        }

        @Test
        void unsupportedContentTypeNamesTheFix() throws Exception {
            mvc.perform(post(GENERAL).contentType(MediaType.TEXT_PLAIN).content("2026-12-20"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"))
                    .andExpect(jsonPath("$.message").value(
                            "Content-Type 'text/plain' is not supported. Send JSON with 'Content-Type: application/json'."));
        }

        @Test
        void wrongMethodNamesTheRightOne() throws Exception {
            mvc.perform(get(GENERAL))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                    .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.message").value("HTTP method GET is not supported for this endpoint. Use POST."));
        }

        @Test
        void clientThatDoesNotAcceptJson() throws Exception {
            mvc.perform(post(GENERAL).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML)
                            .content("{\"originDepartureDate\":\"2026-12-20\"}"))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(jsonPath("$.error").value("NOT_ACCEPTABLE"))
                    .andExpect(jsonPath("$.message").value(startsWith("This API only returns JSON")));
        }

        @Test
        void unknownApiPath() throws Exception {
            mvc.perform(get("/api/v1/booking/unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("No API endpoint exists at this path"));
        }

        @Test
        void rulesAreCacheableWithEtag() throws Exception {
            String etag = mvc.perform(get("/api/v1/booking/rules"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
                    .andExpect(header().exists(HttpHeaders.ETAG))
                    .andExpect(jsonPath("$.general.arpDays").value(60))
                    .andExpect(jsonPath("$.tatkal.acOpeningTime").value("10:00:00"))
                    .andExpect(jsonPath("$.tatkal.classes.length()").value(9))
                    .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

            mvc.perform(get("/api/v1/booking/rules").header(HttpHeaders.IF_NONE_MATCH, etag))
                    .andExpect(status().isNotModified());
        }

        @Test
        void publicHealth() throws Exception {
            mvc.perform(get("/public/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.serverTime").value("2026-09-28T08:00:00+05:30"));
        }
    }
}
