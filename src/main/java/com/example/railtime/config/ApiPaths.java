package com.example.railtime.config;

/** Central URL constants shared by controllers, security and web configuration. */
public final class ApiPaths {

    public static final String BOOKING = "/api/v1/booking";
    public static final String GENERAL_CALCULATE = "/general/calculate";
    public static final String TATKAL_CALCULATE = "/tatkal/calculate";
    public static final String RULES = "/rules";
    public static final String CANCELLATION_CALCULATE = "/cancellation/calculate";
    public static final String CHART_CALCULATE = "/chart/calculate";
    public static final String WINDOW = "/window";
    public static final String PUBLIC = "/public";
    public static final String HEALTH = "/health";

    public static final String[] DOCS = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"};
    public static final String[] STATIC_RESOURCES = {
        "/", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/favicon.svg",
        "/robots.txt", "/site.webmanifest", "/error"
    };

    private ApiPaths() {
    }
}
