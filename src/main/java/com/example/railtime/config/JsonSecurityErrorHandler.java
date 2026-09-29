package com.example.railtime.config;

import com.example.railtime.exception.ApiErrorCode;
import com.example.railtime.exception.ErrorResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.railtime.model.response.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes security rejections in the same {@code ErrorResponse} shape as the rest of the API.
 *
 * <p>No authentication mechanism is configured today, so every rejection is reported as 403.
 * When JWT authentication is added, the entry point can switch to 401 for unauthenticated calls.
 */
@Component
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonSecurityErrorHandler.class);
    private static final String MESSAGE = "Access to this resource is not permitted";

    private final ErrorResponseFactory errors;
    private final JsonMapper jsonMapper;

    public JsonSecurityErrorHandler(ErrorResponseFactory errors, JsonMapper jsonMapper) {
        this.errors = errors;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response);
    }

    private void write(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isBrowserPageRequest(request)) {
            // A person typed or followed a URL that isn't one of our pages: show the friendly
            // "not on the route" page (error.html) rather than a JSON body.
            log.info("event=page_not_found method={} path={}", request.getMethod(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        ErrorResponse body = errors.create(ApiErrorCode.FORBIDDEN, MESSAGE, request.getRequestURI());
        log.info("event=access_denied requestId={} method={} path={}", body.requestId(), request.getMethod(),
                request.getRequestURI());
        response.setStatus(ApiErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }

    /** A GET for an HTML page (not the API, docs or actuator) from a browser. */
    static boolean isBrowserPageRequest(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod()) && accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)
                && !path.startsWith("/api/") && !path.startsWith("/actuator") && !path.startsWith("/v3/");
    }
}
