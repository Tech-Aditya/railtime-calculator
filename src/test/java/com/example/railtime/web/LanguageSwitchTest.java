package com.example.railtime.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.railtime.TestFixtures;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LanguageSwitchTest {

    private static final String HINDI_TITLE = "<title>IRCTC टिकट बुकिंग तारीख कैलकुलेटर</title>";
    private static final String ENGLISH_TITLE = "<title>IRCTC Ticket Booking Date Calculator</title>";

    @TestBean
    Clock clock;

    static Clock clock() {
        return TestFixtures.fixedClock();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void langParameterRendersHindiAndRemembersTheChoice() throws Exception {
        String html = mvc.perform(get("/").param("lang", "hi"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.startsWith("railtime.lang=hi;"),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("SameSite=Lax"))))
                .andExpect(header().string(HttpHeaders.VARY, org.hamcrest.Matchers.containsString("Cookie")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("<html lang=\"hi\">", HINDI_TITLE)
                .contains("60 दिन की बुकिंग अवधि", "तत्काल खुलने का समय", "रिफ़ंड कैलकुलेटर", "चार्ट बनने का समय")
                .contains("<option value=\"SL\" selected=\"selected\">स्लीपर (SL)</option>")
                .contains("<option value=\"CONFIRMED\" selected=\"selected\">कन्फ़र्म</option>")
                .contains("पहला चार्ट पिछले दिन <strong>09:00 PM</strong> तक")
                .contains("प्रति यात्री ₹60 क्लर्केज")
                .contains("Noto+Sans+Devanagari")
                .contains("<meta property=\"og:locale\" content=\"hi_IN\">")
                .contains("<link rel=\"canonical\" href=\"http://localhost:8080/?lang=hi\">")
                .doesNotContain("??");
    }

    @Test
    void todayStripIsRenderedOnTheServerInEachLanguage() throws Exception {
        // Clock: 28 Sep 2026, 08:00 IST — today's opening has just happened; Tatkal windows not yet open.
        String english = html(get("/"));
        assertThat(english)
                .doesNotContain("id=\"today\" class=\"today container\" aria-labelledby=\"today-title\" hidden")
                .contains("data-slot=\"latest\">27 Nov 2026<", "data-slot=\"next\">28 Nov 2026<")
                .contains("data-slot=\"next-when\">Tomorrow, 08:00 AM IST<", "data-slot=\"tatkal\">29 Sep 2026<")
                .contains(">AC · 10:00 AM<", ">Non-AC · 11:00 AM<");

        assertThat(html(get("/").param("lang", "hi")))
                .contains("data-slot=\"latest\">27 नवंबर 2026<", "data-slot=\"next-when\">कल, 08:00 AM IST<")
                .contains(">एसी · 10:00 AM<", ">नॉन-एसी · 11:00 AM<");
    }

    @Test
    void cookieKeepsTheChosenLanguage() throws Exception {
        assertThat(html(get("/").cookie(new Cookie("railtime.lang", "hi")))).contains(HINDI_TITLE);
    }

    @Test
    void browserLanguageIsUsedWhenNothingWasChosen() throws Exception {
        assertThat(html(get("/").header(HttpHeaders.ACCEPT_LANGUAGE, "hi-IN,hi;q=0.9,en;q=0.8"))).contains(HINDI_TITLE);
        assertThat(html(get("/").header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR,en;q=0.5"))).contains(ENGLISH_TITLE);
    }

    @Test
    void explicitChoiceBeatsCookieAndBrowserLanguage() throws Exception {
        String html = html(get("/").param("lang", "en")
                .cookie(new Cookie("railtime.lang", "hi"))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "hi-IN"));

        assertThat(html).contains("<html lang=\"en\">", ENGLISH_TITLE);
    }

    @Test
    void unsupportedLanguagesFallBackToEnglishWithoutACookie() throws Exception {
        mvc.perform(get("/").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertThat(html(get("/").param("lang", "fr"))).contains("<html lang=\"en\">", ENGLISH_TITLE);
        assertThat(html(get("/").cookie(new Cookie("railtime.lang", "xx")))).contains(ENGLISH_TITLE);
    }

    @Test
    void switcherLinksEveryLanguage() throws Exception {
        String html = html(get("/"));

        assertThat(html)
                .contains("href=\"/?lang=en\"", "href=\"/?lang=hi\"", ">हिन्दी<", ">English<")
                .contains("hreflang=\"hi\" href=\"http://localhost:8080/?lang=hi\"")
                .contains("hreflang=\"x-default\" href=\"http://localhost:8080/\"");
    }

    @Test
    void pageDataCarriesTranslationsAndRuleValuesForTheScript() throws Exception {
        String html = html(get("/").param("lang", "hi"));
        String json = html.replaceAll("(?s).*<script type=\"application/json\" id=\"page-data\">(.*?)</script>.*", "$1");
        JsonNode data = jsonMapper.readTree(json);

        assertThat(data.get("lang").asString()).isEqualTo("hi");
        assertThat(data.get("locale").asString()).isEqualTo("hi-IN");
        assertThat(data.get("messages").get("btn.calculate").asString()).isEqualTo("गणना करें");
        assertThat(data.get("messages").get("refund.rule.FLAT_CHARGE").asString()).contains("{0}", "{1}");
        assertThat(data.get("rules").get("refund").get("flatCharges").get("3A").asString()).isEqualTo("180");
        assertThat(data.get("rules").get("refund").get("gstClasses").toString()).contains("\"3A\"").doesNotContain("\"SL\"");
        assertThat(data.get("rules").get("chart").get("effectiveFrom").asString()).isEqualTo("2025-07-10");
        assertThat(json).doesNotContain("<", ">");
    }
}
