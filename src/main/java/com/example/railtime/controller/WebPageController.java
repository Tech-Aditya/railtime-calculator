package com.example.railtime.controller;

import com.example.railtime.i18n.ClientMessages;
import com.example.railtime.i18n.Language;
import com.example.railtime.i18n.LanguageLocaleResolver;
import com.example.railtime.model.TravelClass;
import com.example.railtime.model.response.BookingWindowResponse;
import com.example.railtime.service.BookingWindowService;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.context.MessageSource;
import com.example.railtime.properties.ApplicationProperties;
import com.example.railtime.properties.BookingRulesProperties;
import com.example.railtime.service.BookingRuleService;
import com.example.railtime.util.DateTimeUtil;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page calculator UI. Rule values shown on the page come from the same
 * configuration the API uses, so the page and the API can never disagree.
 */
@Hidden
@Controller
public class WebPageController {

    /** Dropdown order: non-AC first, then AC, then 1A (not a standard Tatkal class) last. */
    static final List<TravelClass> TATKAL_CLASS_ORDER = List.of(
            TravelClass.SLEEPER, TravelClass.SECOND_SITTING, TravelClass.FIRST_CLASS,
            TravelClass.SECOND_AC, TravelClass.THIRD_AC, TravelClass.AC_CHAIR_CAR,
            TravelClass.EXECUTIVE_CHAIR_CAR, TravelClass.AC_THREE_ECONOMY, TravelClass.FIRST_AC);

    private final BookingRuleService ruleService;
    private final BookingRulesProperties rules;
    private final ApplicationProperties app;
    private final Map<String, Object> staticModel;
    private final Map<Language, String> pageData = new EnumMap<>(Language.class);
    private final BookingWindowService windowService;
    private final MessageSource messages;

    public WebPageController(BookingRuleService ruleService, BookingRulesProperties rules, ApplicationProperties app,
                             ClientMessages clientMessages, JsonMapper jsonMapper, BookingWindowService windowService,
                             MessageSource messages) {
        this.ruleService = ruleService;
        this.rules = rules;
        this.app = app;
        this.windowService = windowService;
        this.messages = messages;
        BookingRulesProperties.Chart chart = rules.chart();
        Map<String, Object> clientRules = clientRules(rules);
        for (Language language : Language.values()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lang", language.code());
            data.put("locale", language.intlLocale());
            data.put("messages", clientMessages.forLanguage(language));
            data.put("rules", clientRules);
            pageData.put(language, safeForScriptElement(jsonMapper.writeValueAsString(data)));
        }
        this.staticModel = Map.ofEntries(
                Map.entry("tatkalClasses", TATKAL_CLASS_ORDER),
                Map.entry("arpDays", rules.general().advanceDays()),
                Map.entry("generalOpeningTime", DateTimeUtil.formatTime(rules.general().openingTime())),
                Map.entry("tatkalDaysBefore", rules.tatkal().daysBeforeDeparture()),
                Map.entry("tatkalAcTime", DateTimeUtil.formatTime(rules.tatkal().acOpeningTime())),
                Map.entry("tatkalNonAcTime", DateTimeUtil.formatTime(rules.tatkal().nonAcOpeningTime())),
                Map.entry("chartBandStart", DateTimeUtil.formatTime(chart.previousDayBandStart())),
                Map.entry("chartBandEnd", DateTimeUtil.formatTime(chart.previousDayBandEnd())),
                Map.entry("chartPreviousDayTime", DateTimeUtil.formatTime(chart.previousDayChartTime())),
                Map.entry("chartHoursBefore", chart.firstChartHoursBefore()),
                Map.entry("finalChartMinutes", chart.finalChartMinutesBefore()),
                Map.entry("clerkage", rules.cancellation().clerkage().stripTrailingZeros().toPlainString()));
    }

    @GetMapping("/")
    public String index(Model model, HttpServletRequest request, HttpServletResponse response) {
        // Revalidate on every visit; the ETag filter turns unchanged pages into 304s.
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noCache().getHeaderValue());
        // The language comes from ?lang=, the language cookie or Accept-Language.
        response.setHeader(HttpHeaders.VARY, "Cookie, Accept-Language");
        Language language = LanguageLocaleResolver.resolveLanguage(request);
        model.addAllAttributes(staticModel);
        model.addAttribute("lang", language);
        model.addAttribute("languages", Language.values());
        model.addAttribute("pageData", pageData.get(language));
        model.addAttribute("siteUrl", app.siteUrl());
        model.addAttribute("docsEnabled", app.docs().enabled());
        LocalDate today = ruleService.now().toLocalDate();
        model.addAttribute("today", today);
        model.addAttribute("minDate", today.minusDays(rules.validation().maxDaysInPast()));
        model.addAttribute("maxDate", today.plusDays(rules.validation().maxDaysInFuture()));
        // Rendered on the server so the strip is in place at first paint (no layout shift);
        // app.js then keeps it live (countdown, refresh when a window opens).
        model.addAttribute("todayStrip", todayStrip(windowService.today(), language));
        return "index";
    }

    /** Display strings for the "Today" strip, formatted exactly as app.js formats them. */
    public record TodayStrip(String latest, String next, String nextWhen, String tatkal,
                             String ac, boolean acOpen, String nonAc, boolean nonAcOpen) {
    }

    TodayStrip todayStrip(BookingWindowResponse window, Language language) {
        Locale locale = language.locale();
        DateTimeFormatter medium = DateTimeFormatter.ofPattern(language.mediumDatePattern(), language.dateLocale());
        BookingWindowResponse.NextOpening next = window.nextOpening();
        BookingWindowResponse.TatkalToday tatkal = window.tatkal();
        String opensAt = messages.getMessage("js.time.withZone",
                new Object[] {DateTimeUtil.formatTime(next.opensAt().toLocalTime())}, locale);
        boolean opensToday = next.opensAt().toLocalDate().equals(window.asOf().toLocalDate());
        String open = messages.getMessage("js.today.open", null, locale);
        return new TodayStrip(
                medium.format(window.latestBookableJourneyDate()),
                medium.format(next.journeyDate()),
                messages.getMessage(opensToday ? "js.today.today" : "js.today.tomorrow", new Object[] {opensAt}, locale),
                medium.format(tatkal.journeyDate()),
                messages.getMessage("js.today.ac", new Object[] {tatkal.acOpen() ? open : tatkal.acTimeDisplay()}, locale),
                tatkal.acOpen(),
                messages.getMessage("js.today.nonAc",
                        new Object[] {tatkal.nonAcOpen() ? open : tatkal.nonAcTimeDisplay()}, locale),
                tatkal.nonAcOpen());
    }

    /** Rule values app.js needs to explain refund and chart results in the page's language. */
    private static Map<String, Object> clientRules(BookingRulesProperties rules) {
        BookingRulesProperties.Cancellation c = rules.cancellation();
        BookingRulesProperties.Chart chart = rules.chart();
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("flatAboveHours", c.flatChargeAboveHours());
        refund.put("quarterFromHours", c.quarterChargeFromHours());
        refund.put("cutoffHours", c.confirmedCutoffHours());
        refund.put("racCutoffMinutes", c.racWaitlistCutoffMinutes());
        refund.put("quarterRatePercent", plain(c.quarterRatePercent()));
        refund.put("halfRatePercent", plain(c.halfRatePercent()));
        refund.put("clerkage", plain(c.clerkage()));
        refund.put("gstRatePercent", plain(c.gstRatePercent()));
        refund.put("gstClasses", List.copyOf(new TreeSet<>(c.gstClasses())));
        Map<String, String> flat = new TreeMap<>();
        c.flatCharges().forEach((code, amount) -> flat.put(code, plain(amount)));
        refund.put("flatCharges", flat);

        Map<String, Object> chartRules = new LinkedHashMap<>();
        chartRules.put("finalChartMinutes", chart.finalChartMinutesBefore());
        chartRules.put("effectiveFrom", chart.effectiveFrom().toString());
        return Map.of("refund", refund, "chart", chartRules);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** JSON inside a {@code <script>} element must not contain "</script>" or other markup. */
    static String safeForScriptElement(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }
}
