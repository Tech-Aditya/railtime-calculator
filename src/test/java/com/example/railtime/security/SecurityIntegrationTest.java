package com.example.railtime.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pageIsPublicWithStrictSecurityHeaders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void hstsIsSentOverHttps() throws Exception {
        mvc.perform(get("/").secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    void calculatorApiIsPublicAndCsrfExempt() throws Exception {
        mvc.perform(post("/api/v1/booking/general/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originDepartureDate\":\"2026-12-20\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void staticAssetsArePublic() throws Exception {
        mvc.perform(get("/favicon.svg")).andExpect(status().isOk());
        mvc.perform(get("/robots.txt")).andExpect(status().isOk());
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/configprops", "/admin", "/internal/anything"})
    void everythingElseIsDeniedWithJsonError(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value(path));
    }

    @Test
    void browsersOpeningAnUnknownPageGetTheFriendlyNotFoundPage() throws Exception {
        // sendError(404) → the container renders error.html; API-style requests still get JSON 403 (above).
        mvc.perform(get("/no-such-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/env").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeMethodsOutsideTheApiAreDenied() throws Exception {
        mvc.perform(post("/public/health")).andExpect(status().isForbidden());
    }

    @Test
    void crossOriginCallsAreRejectedByDefault() throws Exception {
        mvc.perform(options("/api/v1/booking/general/calculate")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void swaggerAvailableWhenDocsEnabled() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("RailTime Calculator API"))
                .andExpect(jsonPath("$.paths['/api/v1/booking/general/calculate'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/booking/tatkal/calculate'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ErrorResponse").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
