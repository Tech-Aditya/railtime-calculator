package com.example.railtime.usage;

import com.example.railtime.config.ApiPaths;
import com.example.railtime.usage.UsageMetrics.Calculator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Counts page visits and successful calculations after the request has been handled, so the
 * controllers and calculation code are untouched. Only the fact that a request happened is
 * recorded — never who made it.
 */
public class UsageTrackingInterceptor implements HandlerInterceptor {

    static final Map<String, Calculator> CALCULATIONS = Map.of(
            ApiPaths.BOOKING + ApiPaths.GENERAL_CALCULATE, Calculator.GENERAL,
            ApiPaths.BOOKING + ApiPaths.TATKAL_CALCULATE, Calculator.TATKAL,
            ApiPaths.BOOKING + ApiPaths.CANCELLATION_CALCULATE, Calculator.REFUND,
            ApiPaths.BOOKING + ApiPaths.CHART_CALCULATE, Calculator.CHART);

    /** Crawlers, link-preview fetchers and monitors are not visitors. */
    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|preview|monitor|uptime|curl|wget|python|java/|headless|lighthouse|facebookexternalhit");

    private final UsageMetrics metrics;

    public UsageTrackingInterceptor(UsageMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex != null || response.getStatus() != HttpStatus.OK.value()) {
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Calculator calculator = CALCULATIONS.get(path);
        if (calculator != null) {
            if ("POST".equals(request.getMethod())) {
                metrics.recordCalculation(calculator);
            }
        } else if ("GET".equals(request.getMethod()) && !isBot(request)) {
            metrics.recordVisit(); // the interceptor is registered for page paths only
        }
    }

    static boolean isBot(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return agent == null || agent.isBlank() || BOT.matcher(agent.toLowerCase(Locale.ROOT)).find();
    }
}
