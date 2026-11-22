package com.lezzat.b2b2c.frozenfood.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record DeliveryRouteResponse(
        Koordinat toko,
        String sumberRute,
        List<Perjalanan> perjalanan,
        List<String> peringatan
) {
    public record Koordinat(double latitude, double longitude) {
    }

    public record GarisRute(String type, List<List<Double>> coordinates) {
    }

    public record Perjalanan(
            int perjalananKe,
            LocalDateTime berangkatPerkiraan,
            LocalDateTime pulangPerkiraan,
            BigDecimal totalJarakKm,
            int totalMenit,
            BigDecimal beratMuatanKg,
            BigDecimal volumeMuatanLiter,
            boolean melanggarRantaiDingin,
            GarisRute garisRute,
            List<Stop> stops
    ) {
    }

    public record Stop(
            int urutan,
            UUID idDelivery,
            UUID idOrder,
            String alamatTujuan,
            double latitude,
            double longitude,
            BigDecimal jarakDariSebelumnyaKm,
            int menitDariBerangkat,
            LocalDateTime estimasiTiba
    ) {
    }
}
