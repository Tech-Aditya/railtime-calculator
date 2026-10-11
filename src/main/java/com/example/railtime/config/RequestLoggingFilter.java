package com.example.railtime.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a short id and writes one access line per page or API call.
 *
 * <ul>
 *   <li>The id is put in the logging context (MDC key {@value #MDC_KEY}), so every log line written
 *       while handling the request carries it: in the dev console pattern and as a field in the
 *       production JSON logs. Error responses quote the same id, and it is returned in the
 *       {@value #HEADER} response header.</li>
 *   <li>An incoming {@value #HEADER} from a proxy is reused when it looks safe, so a request can be
 *       followed across systems; anything else gets a fresh random id.</li>
 *   <li>Static assets, health checks and other actuator calls are not logged: they would drown the
 *       useful lines (Render probes health every few seconds).</li>
 * </ul>
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern QUIET_PATHS = Pattern.compile(
            "^/(css|js|images|actuator|swagger-ui|v3/api-docs)(/.*)?$"
                    + "|^/(favicon\\.(ico|svg)|apple-touch-icon(-precomposed)?\\.png|robots\\.txt|site\\.webmanifest)$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestIdFrom(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!QUIET_PATHS.matcher(path).matches()) {
                long millis = (System.nanoTime() - start) / 1_000_000;
                int status = response.getStatus();
                if (status >= 500) {
                    log.warn("event=request method={} path={} status={} durationMs={}", request.getMethod(), path, status, millis);
                } else {
                    log.info("event=request method={} path={} status={} durationMs={}", request.getMethod(), path, status, millis);
                }
            }
            MDC.remove(MDC_KEY);
        }
    }

    /** The caller's id when it is short and plain; otherwise a new random 8-hex-character id. */
    static String requestIdFrom(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    }

    /** The current request's id, or null outside a request. */
    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}
