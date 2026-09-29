package com.example.railtime.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.railtime.TestFixtures;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebPageTest {

    @TestBean
    Clock clock;

    static Clock clock() {
        return TestFixtures.fixedClock();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void rendersCalculatorPageWithSeoMetadataAndConfiguredRules() throws Exception {
        String html = mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("<title>IRCTC Ticket Booking Date Calculator</title>")
                .contains("Calculate IRCTC general reservation and Tatkal booking opening times, cancellation refunds and reservation chart times based on official railway rules.")
                .contains("property=\"og:image\" content=\"http://localhost:8080/images/og-image.png\"")
                .contains("is not affiliated with or operated by IRCTC or Indian Railways")
                .contains("Know exactly when your ticket booking opens.")
                .contains("60-Day Booking Window")
                .contains("Tatkal Opening Time")
                .contains("<option value=\"SL\" selected=\"selected\">Sleeper (SL)</option>")
                .contains("<option value=\"1A\">First AC (1A)</option>")
                .contains("min=\"2025-09-28\"")
                .contains("max=\"2028-09-27\"")
                .contains("Fill in any calculator above to see your result here.")
                .containsPattern("/css/app-[0-9a-f]{32}\\.css")
                .containsPattern("/js/app-[0-9a-f]{32}\\.js")
                .contains("© 2026 RailTime");
    }

    @Test
    void rendersPlanningToolsFromConfiguredRules() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("Refund Calculator", "Chart Preparation Time")
                .contains("data-endpoint=\"/api/v1/booking/cancellation/calculate\"")
                .contains("data-endpoint=\"/api/v1/booking/chart/calculate\"")
                .contains("data-endpoint=\"/api/v1/booking/window\"")
                .contains("<option value=\"CONFIRMED_TATKAL\">Confirmed Tatkal</option>")
                .contains("Departs 05:00 AM – 02:00 PM")
                .contains("<strong>09:00 PM</strong> the previous day")
                .contains("<strong>8 hours</strong> before departure")
                .contains("<strong>30 minutes</strong> before departure")
                .contains("pay ₹60 clerkage per passenger")
                .contains("https://contents.irctc.co.in/en/eticketCancel.html");
    }

    @Test
    void tatkalClassesFollowTheSpecifiedOrder() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        assertThat(html).containsSubsequence(
                "value=\"SL\"", "value=\"2S\"", "value=\"FC\"", "value=\"2A\"", "value=\"3A\"",
                "value=\"CC\"", "value=\"EC\"", "value=\"3E\"", "value=\"1A\"");
    }

    @Test
    void navigationListsEveryCalculator() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String nav = html.replaceAll("(?s).*<nav id=\"site-nav\"(.*?)</nav>.*", "$1");

        assertThat(nav).contains(">Home<", ">General<", ">Tatkal<", ">Refund<", ">Chart<", ">API<");
        assertThat(html)
                .contains("class=\"theme-toggle\" aria-label=\"Switch to dark mode\"")
                .containsPattern("<script src=\"/js/theme-[0-9a-f]{32}\\.js\"></script>");
        assertThat(html).doesNotContainIgnoringCase("find trains").doesNotContain("find-trains");
    }

    @Test
    void fingerprintedAssetsAreCachedLongTerm() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String cssPath = html.replaceAll("(?s).*href=\"(/css/app-[0-9a-f]{32}\\.css)\".*", "$1");

        mvc.perform(get(cssPath))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public"));
    }
}
