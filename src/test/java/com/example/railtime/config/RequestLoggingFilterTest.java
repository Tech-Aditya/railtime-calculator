package com.example.railtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestLoggingFilterTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string(RequestLoggingFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void errorBodyQuotesTheSameIdAsTheHeader() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/booking/general/calculate")
                        .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();

        String bodyId = jsonMapper.readTree(response.getContentAsString()).get("requestId").asString();
        assertThat(bodyId).isEqualTo(response.getHeader(RequestLoggingFilter.HEADER));
    }

    @Test
    void securityRejectionsUseTheRequestIdToo() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden()).andReturn().getResponse();

        assertThat(jsonMapper.readTree(response.getContentAsString()).get("requestId").asString())
                .isEqualTo(response.getHeader(RequestLoggingFilter.HEADER));
    }

    @Test
    void aSafeIncomingIdIsReused() throws Exception {
        mvc.perform(get("/public/health").header(RequestLoggingFilter.HEADER, "render-abc123"))
                .andExpect(header().string(RequestLoggingFilter.HEADER, "render-abc123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"has spaces", "<script>", "an-id-that-is-far-too-long-to-be-trusted-as-a-request-id-from-a-proxy"})
    void unsafeIncomingIdsAreReplaced(String incoming) {
        assertThat(RequestLoggingFilter.requestIdFrom(incoming)).matches("[0-9a-f]{8}");
    }

    /** Browsers and iOS request these on their own; they must be served, not logged as errors. */
    @ParameterizedTest
    @ValueSource(strings = {"/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png",
            "/images/icon-192.png", "/images/icon-512.png", "/images/icon-maskable-512.png", "/site.webmanifest"})
    void browserIconRequestsAreServed(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }
}
