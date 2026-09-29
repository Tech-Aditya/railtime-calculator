package com.example.railtime.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The prod profile keeps the calculator working but hides API documentation by default. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionProfileTest {

    @Autowired
    MockMvc mvc;

    @Test
    void swaggerIsDisabledByDefault() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isForbidden());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isForbidden());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isForbidden());
    }

    @Test
    void calculatorStillWorks() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/booking/tatkal/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originDepartureDate\":\"2027-06-01\",\"travelClass\":\"SL\"}"))
                .andExpect(status().isOk());
    }
}
