package com.example.railtime.config;

import com.example.railtime.exception.ApiErrorCode;
import com.example.railtime.exception.ErrorResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        log.info("event=access_denied method={} path={}", request.getMethod(), request.getRequestURI());
        response.setStatus(ApiErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(),
                errors.create(ApiErrorCode.FORBIDDEN, MESSAGE, request.getRequestURI()));
    }
}
