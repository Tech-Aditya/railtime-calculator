package com.example.railtime.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.railtime.TestFixtures;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

/** What is counted, and what is not. Assertions use deltas because the context is shared across tests. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageTrackingIntegrationTest {

    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140 Safari/537.36";

    @TestBean
    Clock clock;

    static Clock clock() {
        return TestFixtures.fixedClock();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    UsageMetrics metrics;

    @Test
    void countsPageVisitsFromBrowsersButNotBotsOrApiCalls() throws Exception {
        long before = metrics.snapshot().visits().total();

        mvc.perform(get("/").header(HttpHeaders.USER_AGENT, BROWSER)).andExpect(status().isOk());
        mvc.perform(get("/tatkal").header(HttpHeaders.USER_AGENT, BROWSER)).andExpect(status().isOk());
        mvc.perform(get("/").header(HttpHeaders.USER_AGENT, "Googlebot/2.1 (+http://www.google.com/bot.html)"));
        mvc.perform(get("/")); // no user agent: not a browser
        mvc.perform(get("/api/v1/booking/window").header(HttpHeaders.USER_AGENT, BROWSER));
        mvc.perform(get("/api/v1/usage").header(HttpHeaders.USER_AGENT, BROWSER));

        assertThat(metrics.snapshot().visits().total() - before).isEqualTo(2);
    }

    @Test
    void countsOnlySuccessfulCalculations() throws Exception {
        UsageResponse.Calculations before = metrics.snapshot().calculations();

        mvc.perform(post("/api/v1/booking/general/calculate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"originDepartureDate\":\"2026-12-20\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/booking/tatkal/calculate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"originDepartureDate\":\"2026-12-20\",\"travelClass\":\"3A\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/booking/chart/calculate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"departureDate\":\"2026-12-20\",\"departureTime\":\"10:30\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/booking/general/calculate").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest()); // not counted

        UsageResponse.Calculations after = metrics.snapshot().calculations();
        assertThat(after.general() - before.general()).isEqualTo(1);
        assertThat(after.tatkal() - before.tatkal()).isEqualTo(1);
        assertThat(after.chart() - before.chart()).isEqualTo(1);
        assertThat(after.refund() - before.refund()).isZero();
        assertThat(after.total() - before.total()).isEqualTo(3);
    }

    @Test
    void usageEndpointIsPublicAndNeverCached() throws Exception {
        mvc.perform(get("/api/v1/usage"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.since").exists())
                .andExpect(jsonPath("$.visits.thisWeek").isNumber())
                .andExpect(jsonPath("$.calculations.refund").isNumber())
                .andExpect(jsonPath("$.daily.length()").value(1)); // counting started today (fixed clock)
    }
}
