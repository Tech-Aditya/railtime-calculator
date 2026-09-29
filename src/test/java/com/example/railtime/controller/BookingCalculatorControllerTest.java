package com.example.railtime.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.railtime.model.TravelClass;
import com.example.railtime.model.request.ReservationCalculationRequest;
import com.example.railtime.model.request.TatkalCalculationRequest;
import com.example.railtime.service.ReservationCalculatorService;
import com.example.railtime.service.TatkalCalculatorService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Controller behaviour in isolation from business logic: binding, validation gatekeeping, error mapping. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingCalculatorControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReservationCalculatorService reservationService;

    @MockitoBean
    TatkalCalculatorService tatkalService;

    @Test
    void bindsRequestAndDelegatesToService() throws Exception {
        mvc.perform(post("/api/v1/booking/tatkal/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originDepartureDate\":\"2026-12-20\",\"travelClass\":\"3a\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<TatkalCalculationRequest> captor = ArgumentCaptor.forClass(TatkalCalculationRequest.class);
        verify(tatkalService).calculate(captor.capture());
        assertThat(captor.getValue().originDepartureDate()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(captor.getValue().travelClass()).isEqualTo(TravelClass.THIRD_AC);
    }

    @Test
    void invalidRequestsNeverReachTheService() throws Exception {
        mvc.perform(post("/api/v1/booking/general/calculate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/booking/tatkal/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originDepartureDate\":\"2026-12-20\",\"travelClass\":\"ZZ\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reservationService, tatkalService);
    }

    @Test
    void unexpectedFailureIsGenericAndHidesInternals() throws Exception {
        when(reservationService.calculate(any(ReservationCalculationRequest.class)))
                .thenThrow(new IllegalStateException("secret internal detail"));

        String body = mvc.perform(post("/api/v1/booking/general/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originDepartureDate\":\"2026-12-20\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret internal detail").doesNotContain("at com.example");
        // The message carries the same reference as the requestId field (and the log line).
        String requestId = body.replaceAll("(?s).*\"requestId\":\"([0-9a-f]{8})\".*", "$1");
        assertThat(requestId).matches("[0-9a-f]{8}");
        assertThat(body).contains("Something went wrong on our side. Please try again later. If it keeps happening, quote reference "
                + requestId + ".");
    }
}
