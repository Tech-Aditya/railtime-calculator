package com.example.railtime.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
                .contains(">Next general booking<", ">Other calculators<")
                .doesNotContain("General reservation for journeys on")
                .contains("id=\"result-general\"", "id=\"result-tatkal\"", "id=\"result-refund\"", "id=\"result-chart\"")
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
                .contains("data-window-endpoint=\"/api/v1/booking/window\"")
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
    void sidebarListsEveryPageInGroups() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String nav = html.replaceAll("(?s).*<nav class=\"sb-nav\"(.*?)</nav>.*", "$1");

        assertThat(nav).containsSubsequence(
                ">Main<", ">Dashboard<",
                ">Calculators<", ">General Booking<", ">Tatkal<", ">Refund Calculator<", ">Chart Preparation<",
                ">Tools<", ">Booking Status<", ">Reminders<", ">Railway Rules<",
                ">Developer<", ">API<");
        assertThat(nav).contains("href=\"/tatkal\"", "data-nav=\"tatkal\"", "href=\"/swagger-ui.html\"");
        assertThat(html)
                .contains("class=\"icon-btn theme-toggle\" aria-label=\"Switch to dark mode\"")
                .contains("class=\"sb-link sb-collapse\"")
                .doesNotContain("id=\"site-nav\"") // no duplicate top navigation
                .containsPattern("<script src=\"/js/theme-[0-9a-f]{32}\\.js\"></script>");
        assertThat(html).doesNotContainIgnoringCase("find trains").doesNotContain("find-trains");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "/, dashboard, IRCTC Ticket Booking Date Calculator",
            "/general, general, General Booking · RailTime Calculator",
            "/tatkal, tatkal, Tatkal · RailTime Calculator",
            "/refund, refund, Refund Calculator · RailTime Calculator",
            "/chart, chart, Chart Preparation · RailTime Calculator",
            "/status, status, Booking Status · RailTime Calculator",
            "/reminders, reminders, Reminders · RailTime Calculator",
            "/rules, rules, Railway Rules · RailTime Calculator"
    })
    void everyPageHasItsOwnUrl(String path, String key, String title) throws Exception {
        String html = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("<title>" + title + "</title>")
                .contains("data-page=\"" + key + "\"")
                .contains("<link rel=\"canonical\" href=\"http://localhost:8080" + path + "\">")
                .containsPattern("data-nav=\"" + key + "\"[^>]*aria-current=\"page\"");
        // Only this page's view is visible; the other seven are hidden until app.js switches to them.
        String viewTag = html.replaceAll("(?s).*(<section class=\"view[^\"]*\" data-view=\"" + key + "\"[^>]*>).*", "$1");
        assertThat(viewTag).startsWith("<section").doesNotContain("hidden");
        assertThat(html.split("data-view=\"[a-z]+\"[^>]*hidden=\"hidden\"", -1)).hasSize(8);
        assertThat(html.split("aria-current=\"page\"", -1)).hasSizeLessThanOrEqualTo(3); // sidebar (+ calculator tab)
    }

    @ParameterizedTest(name = "{0}: calculator in the {1} slot")
    @CsvSource({"/, dashboard", "/general, general", "/tatkal, general"})
    void generalCalculatorIsRenderedOnceInTheRightPage(String path, String slot) throws Exception {
        String html = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();

        // One form, one result area — shared by the Dashboard and the General Booking page.
        assertThat(html.split("id=\"general-form\"", -1)).hasSize(2);
        assertThat(html.split("id=\"result-general\"", -1)).hasSize(2);
        assertThat(html).containsPattern("data-general-slot=\"" + slot + "-card\"><article id=\"general-card\"")
                .containsPattern("data-general-slot=\"" + slot + "-result\"><div class=\"result-region kind-general\" id=\"result-general\"");
    }

    @Test
    void dashboardOffersTheOtherCalculatorsAsShortcuts() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String shortcuts = html.replaceAll("(?s).*<div class=\"quick-grid\">(.*?)</div>.*", "$1");

        assertThat(shortcuts).contains("href=\"/tatkal\"", "href=\"/refund\"", "href=\"/chart\"").doesNotContain("href=\"/general\"");
        assertThat(html).doesNotContain("Booking status</span>"); // no duplicate link to the sidebar page
    }

    @Test
    void sidebarPagesMatchTheControllerMapping() {
        assertThat(com.example.railtime.controller.Page.PATHS)
                .containsExactly("/", "/general", "/tatkal", "/refund", "/chart", "/status", "/reminders", "/rules");
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
