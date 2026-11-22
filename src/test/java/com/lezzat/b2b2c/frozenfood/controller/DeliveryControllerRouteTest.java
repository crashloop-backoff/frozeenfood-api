package com.lezzat.b2b2c.frozenfood.controller;

import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.GarisRute;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Koordinat;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Perjalanan;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Stop;
import com.lezzat.b2b2c.frozenfood.exception.GlobalExceptionHandler;
import com.lezzat.b2b2c.frozenfood.exception.InvalidDeliveryRouteException;
import com.lezzat.b2b2c.frozenfood.service.DeliveryService;
import com.lezzat.b2b2c.frozenfood.service.core.DeliveryRoutePlanner;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryControllerRouteTest {

    private DeliveryService deliveryService;
    private DeliveryRoutePlanner deliveryRoutePlanner;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deliveryService = mock(DeliveryService.class);
        deliveryRoutePlanner = mock(DeliveryRoutePlanner.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DeliveryController(deliveryService, deliveryRoutePlanner))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void postRute_mengembalikanKontrakJsonYangSesuai() throws Exception {
        UUID idDeliv = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idOrder = UUID.fromString("22222222-2222-2222-2222-222222222222");

        DeliveryRouteResponse response = new DeliveryRouteResponse(
                new Koordinat(-7.756318, 110.371033),
                "RUTE_ASLI",
                List.of(new Perjalanan(
                        1,
                        LocalDateTime.of(2026, 11, 22, 10, 20, 0),
                        LocalDateTime.of(2026, 11, 22, 10, 45, 0),
                        new BigDecimal("8.00"),
                        25,
                        new BigDecimal("4.50"),
                        new BigDecimal("10.00"),
                        false,
                        new GarisRute("LineString", List.of(
                                List.of(110.371033, -7.756318),
                                List.of(110.365000, -7.792500),
                                List.of(110.371033, -7.756318)
                        )),
                        List.of(new Stop(
                                1,
                                idDeliv,
                                idOrder,
                                "Jl. Malioboro No. 10, Yogyakarta",
                                -7.792500,
                                110.365000,
                                new BigDecimal("4.00"),
                                10,
                                LocalDateTime.of(2026, 11, 22, 10, 30, 0)
                        ))
                )),
                List.of()
        );
        when(deliveryRoutePlanner.rencanakanRute(List.of(idDeliv))).thenReturn(response);

        mockMvc.perform(post("/api/deliveries/rute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idDeliveries": ["11111111-1111-1111-1111-111111111111"] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toko.latitude").value(-7.756318))
                .andExpect(jsonPath("$.toko.longitude").value(110.371033))
                .andExpect(jsonPath("$.sumberRute").value("RUTE_ASLI"))
                .andExpect(jsonPath("$.perjalanan[0].perjalananKe").value(1))
                .andExpect(jsonPath("$.perjalanan[0].berangkatPerkiraan").value("2026-11-22T10:20:00"))
                .andExpect(jsonPath("$.perjalanan[0].pulangPerkiraan").value("2026-11-22T10:45:00"))
                .andExpect(jsonPath("$.perjalanan[0].totalJarakKm").value(8.0))
                .andExpect(jsonPath("$.perjalanan[0].totalMenit").value(25))
                .andExpect(jsonPath("$.perjalanan[0].beratMuatanKg").value(4.5))
                .andExpect(jsonPath("$.perjalanan[0].volumeMuatanLiter").value(10.0))
                .andExpect(jsonPath("$.perjalanan[0].melanggarRantaiDingin").value(false))
                .andExpect(jsonPath("$.perjalanan[0].garisRute.type").value("LineString"))
                .andExpect(jsonPath("$.perjalanan[0].garisRute.coordinates[0][0]").value(110.371033))
                .andExpect(jsonPath("$.perjalanan[0].stops[0].urutan").value(1))
                .andExpect(jsonPath("$.perjalanan[0].stops[0].idDelivery").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.perjalanan[0].stops[0].idOrder").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.perjalanan[0].stops[0].estimasiTiba").value("2026-11-22T10:30:00"))
                .andExpect(jsonPath("$.peringatan").isEmpty());
    }

    @Test
    void postRute_validasiGagalMengembalikan400() throws Exception {
        when(deliveryRoutePlanner.rencanakanRute(anyList()))
                .thenThrow(new InvalidDeliveryRouteException("Daftar idDeliveries mengandung ID duplikat"));

        mockMvc.perform(post("/api/deliveries/rute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idDeliveries": ["11111111-1111-1111-1111-111111111111"] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Daftar idDeliveries mengandung ID duplikat"));
    }

    @Test
    void postRute_fallbackPerkiraanMenyertakanGarisRuteNull() throws Exception {
        UUID idDeliv = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idOrder = UUID.fromString("22222222-2222-2222-2222-222222222222");

        DeliveryRouteResponse response = new DeliveryRouteResponse(
                new Koordinat(-7.756318, 110.371033),
                "PERKIRAAN",
                List.of(new Perjalanan(
                        1,
                        LocalDateTime.of(2026, 11, 22, 10, 20, 0),
                        LocalDateTime.of(2026, 11, 22, 10, 51, 0),
                        new BigDecimal("10.60"),
                        31,
                        new BigDecimal("3.00"),
                        new BigDecimal("6.00"),
                        false,
                        null,
                        List.of(new Stop(
                                1,
                                idDeliv,
                                idOrder,
                                "Jl. Malioboro No. 10, Yogyakarta",
                                -7.792500,
                                110.365000,
                                new BigDecimal("5.30"),
                                13,
                                LocalDateTime.of(2026, 11, 22, 10, 33, 0)
                        ))
                )),
                List.of("Rute memakai estimasi garis lurus karena layanan peta tidak tersedia")
        );
        when(deliveryRoutePlanner.rencanakanRute(List.of(idDeliv))).thenReturn(response);

        mockMvc.perform(post("/api/deliveries/rute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idDeliveries": ["11111111-1111-1111-1111-111111111111"] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sumberRute").value("PERKIRAAN"))
                .andExpect(jsonPath("$.perjalanan[0].garisRute").value(nullValue()))
                .andExpect(content().string(containsString("\"garisRute\":null")))
                .andExpect(jsonPath("$.peringatan[0]").value(
                        "Rute memakai estimasi garis lurus karena layanan peta tidak tersedia"));
    }

    @Test
    void postRute_idTidakDitemukanMengembalikan404() throws Exception {
        when(deliveryRoutePlanner.rencanakanRute(anyList()))
                .thenThrow(new EntityNotFoundException("Delivery tidak ditemukan"));

        mockMvc.perform(post("/api/deliveries/rute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idDeliveries": ["11111111-1111-1111-1111-111111111111"] }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Delivery tidak ditemukan"));
    }
}
