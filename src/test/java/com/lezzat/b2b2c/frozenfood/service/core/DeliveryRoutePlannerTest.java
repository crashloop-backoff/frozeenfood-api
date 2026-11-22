package com.lezzat.b2b2c.frozenfood.service.core;

import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Perjalanan;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Stop;
import com.lezzat.b2b2c.frozenfood.entity.Delivery;
import com.lezzat.b2b2c.frozenfood.entity.Order;
import com.lezzat.b2b2c.frozenfood.entity.OrderItem;
import com.lezzat.b2b2c.frozenfood.entity.Product;
import com.lezzat.b2b2c.frozenfood.entity.ProductBatch;
import com.lezzat.b2b2c.frozenfood.exception.InvalidDeliveryRouteException;
import com.lezzat.b2b2c.frozenfood.repository.DeliveryRepository;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.GeoJsonLineString;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.TitikKoordinat;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.TripHasil;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.TripLeg;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DeliveryRoutePlannerTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final ZonedDateTime SEKARANG = ZonedDateTime.of(2026, 11, 22, 10, 0, 0, 0, WIB);

    private DeliveryRepository deliveryRepository;
    private RouteDistanceClient routeDistanceClient;
    private DeliveryProperties props;
    private DeliveryRoutePlanner planner;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        routeDistanceClient = mock(RouteDistanceClient.class);
        props = new DeliveryProperties();
        // Unit produk dianggap dalam kg & liter langsung agar angka tes mudah diverifikasi
        props.getUnit().setWeightToKg(1.0);
        props.getUnit().setVolumeToLiter(1.0);
        planner = new DeliveryRoutePlanner(deliveryRepository, routeDistanceClient, props);
    }

    // (a) Urutan hasil trip dipakai + perhitungan waktu pakai kecepatan konfigurasi (bukan durasi OSRM)
    @Test
    void urutanHasilTripDipakaiDanHitungWaktuDenganKecepatanKonfigurasi() {
        Delivery d1 = buatDelivery("Jl. Pertama", -7.7600, 110.3750, 3.0, 8.0);
        Delivery d2 = buatDelivery("Jl. Kedua", -7.7700, 110.3800, 4.5, 10.0);
        Delivery d3 = buatDelivery("Jl. Ketiga", -7.7800, 110.3650, 5.0, 12.0);

        List<UUID> ids = List.of(d1.getIdDelivery(), d2.getIdDelivery(), d3.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d1, d2, d3));

        // OSRM mengurutkan: Toko(0) -> d2(input 2 -> pos 1) -> d3(input 3 -> pos 2) -> d1(input 1 -> pos 3) -> Toko
        // Sengaja pasang duration OSRM sangat besar (9999 detik) untuk memastikan planner memakai kecepatan 25 km/jam.
        TripHasil tripHasil = new TripHasil(
                List.of(0, 3, 1, 2),
                List.of(
                        new TripLeg(2000.0, 9999.0), // 2.00 km -> ceil(2*60/25) = 5 menit
                        new TripLeg(5000.0, 9999.0), // 5.00 km -> ceil(5*60/25) = 12 menit
                        new TripLeg(4000.0, 9999.0), // 4.00 km -> ceil(4*60/25) = 10 menit
                        new TripLeg(6000.0, 9999.0)  // pulang 6.00 km -> ceil(6*60/25) = 15 menit
                ),
                new GeoJsonLineString("LineString", List.of(
                        List.of(110.371033, -7.756318),
                        List.of(110.3800, -7.7700),
                        List.of(110.3650, -7.7800),
                        List.of(110.3750, -7.7600),
                        List.of(110.371033, -7.756318)
                ))
        );
        when(routeDistanceClient.trip(anyList())).thenReturn(Optional.of(tripHasil));

        DeliveryRouteResponse res = planner.rencanakanRute(ids, SEKARANG);

        assertEquals("RUTE_ASLI", res.sumberRute());
        assertEquals(props.getStore().getLatitude(), res.toko().latitude());
        assertEquals(props.getStore().getLongitude(), res.toko().longitude());
        assertTrue(res.peringatan().isEmpty());
        assertEquals(1, res.perjalanan().size());

        Perjalanan p = res.perjalanan().get(0);
        assertEquals(1, p.perjalananKe());
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 20, 0), p.berangkatPerkiraan());
        // Kumulatif: stop1=5 (+5=10), stop2=22 (+5=27), stop3=37 (+5=42), pulang +15 = 57 menit
        assertEquals(57, p.totalMenit());
        assertEquals(LocalDateTime.of(2026, 11, 22, 11, 17, 0), p.pulangPerkiraan());
        assertEquals(new BigDecimal("17.00"), p.totalJarakKm());
        assertEquals(new BigDecimal("12.50"), p.beratMuatanKg());
        assertEquals(new BigDecimal("30.00"), p.volumeMuatanLiter());
        assertFalse(p.melanggarRantaiDingin());
        assertNotNull(p.garisRute());
        assertEquals("LineString", p.garisRute().type());

        assertEquals(3, p.stops().size());
        Stop s1 = p.stops().get(0);
        Stop s2 = p.stops().get(1);
        Stop s3 = p.stops().get(2);

        assertEquals(1, s1.urutan());
        assertEquals(d2.getIdDelivery(), s1.idDelivery());
        assertEquals(new BigDecimal("2.00"), s1.jarakDariSebelumnyaKm());
        assertEquals(5, s1.menitDariBerangkat());
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 25, 0), s1.estimasiTiba());

        assertEquals(2, s2.urutan());
        assertEquals(d3.getIdDelivery(), s2.idDelivery());
        assertEquals(new BigDecimal("5.00"), s2.jarakDariSebelumnyaKm());
        assertEquals(22, s2.menitDariBerangkat());
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 42, 0), s2.estimasiTiba());

        assertEquals(3, s3.urutan());
        assertEquals(d1.getIdDelivery(), s3.idDelivery());
        assertEquals(new BigDecimal("4.00"), s3.jarakDariSebelumnyaKm());
        assertEquals(37, s3.menitDariBerangkat());
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 57, 0), s3.estimasiTiba());
    }

    // (b1) Pemecahan perjalanan karena batas rantai dingin (> 60 menit)
    @Test
    void pecahPerjalananSaatMelebihiBatasRantaiDingin() {
        Delivery d1 = buatDelivery("Titik 1", -7.76, 110.38, 2.0, 5.0);
        Delivery d2 = buatDelivery("Titik 2", -7.77, 110.39, 2.0, 5.0);
        Delivery d3 = buatDelivery("Titik 3", -7.78, 110.40, 2.0, 5.0);

        List<UUID> ids = List.of(d1.getIdDelivery(), d2.getIdDelivery(), d3.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d1, d2, d3));

        GeoJsonLineString dummyGeom = new GeoJsonLineString("LineString", List.of(
                List.of(110.371033, -7.756318), List.of(110.38, -7.76)));

        when(routeDistanceClient.trip(anyList())).thenAnswer(inv -> {
            List<TitikKoordinat> titik = inv.getArgument(0);
            if (titik.size() == 4) {
                // 3 stop: d1=10km(24m, +5=29), d2=10km(24m -> 53m, +5=58), d3=5km(12m -> 70m > 60m -> PECAH!)
                return Optional.of(new TripHasil(
                        List.of(0, 1, 2, 3),
                        List.of(
                                new TripLeg(10000, 0),
                                new TripLeg(10000, 0),
                                new TripLeg(5000, 0),
                                new TripLeg(12000, 0)
                        ),
                        dummyGeom
                ));
            } else if (titik.size() == 3) {
                // Recalculate perjalanan 1: [d1, d2]
                return Optional.of(new TripHasil(
                        List.of(0, 1, 2),
                        List.of(
                                new TripLeg(10000, 0),
                                new TripLeg(10000, 0),
                                new TripLeg(12000, 0)
                        ),
                        dummyGeom
                ));
            } else {
                // Perjalanan 2: [d3]
                return Optional.of(new TripHasil(
                        List.of(0, 1),
                        List.of(
                                new TripLeg(12000, 0),
                                new TripLeg(12000, 0)
                        ),
                        dummyGeom
                ));
            }
        });

        DeliveryRouteResponse res = planner.rencanakanRute(ids, SEKARANG);

        assertEquals("RUTE_ASLI", res.sumberRute());
        assertEquals(2, res.perjalanan().size());

        Perjalanan p1 = res.perjalanan().get(0);
        assertEquals(1, p1.perjalananKe());
        assertFalse(p1.melanggarRantaiDingin());
        assertEquals(List.of(d1.getIdDelivery(), d2.getIdDelivery()),
                p1.stops().stream().map(Stop::idDelivery).toList());
        assertEquals(24, p1.stops().get(0).menitDariBerangkat());
        assertEquals(53, p1.stops().get(1).menitDariBerangkat());

        Perjalanan p2 = res.perjalanan().get(1);
        assertEquals(2, p2.perjalananKe());
        assertFalse(p2.melanggarRantaiDingin());
        assertEquals(List.of(d3.getIdDelivery()),
                p2.stops().stream().map(Stop::idDelivery).toList());
        assertEquals(29, p2.stops().get(0).menitDariBerangkat());

        verify(routeDistanceClient, times(3)).trip(anyList());
    }

    // (b2) Satu titik sendirian sudah > 60 menit -> tetap dimasukkan, tandai melanggarRantaiDingin=true, tidak loop tak terbatas
    @Test
    void satuTitikSendirianMelebihiRantaiDinginTetapDimasukkanDenganPeringatan() {
        Delivery dJauh = buatDelivery("Titik Jauh", -7.85, 110.45, 2.0, 5.0);
        List<UUID> ids = List.of(dJauh.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(dJauh));

        // 26 km pada 25 km/jam = 62.4 -> 63 menit (> 60 menit)
        when(routeDistanceClient.trip(anyList())).thenReturn(Optional.of(new TripHasil(
                List.of(0, 1),
                List.of(new TripLeg(26000, 0), new TripLeg(26000, 0)),
                new GeoJsonLineString("LineString", List.of(List.of(110.37, -7.75), List.of(110.45, -7.85)))
        )));

        DeliveryRouteResponse res = planner.rencanakanRute(ids, SEKARANG);

        assertEquals(1, res.perjalanan().size());
        Perjalanan p = res.perjalanan().get(0);
        assertTrue(p.melanggarRantaiDingin());
        assertEquals(1, p.stops().size());
        assertEquals(63, p.stops().get(0).menitDariBerangkat());
        assertTrue(res.peringatan().stream().anyMatch(w -> w.contains("rantai dingin")
                && w.contains(dJauh.getIdDelivery().toString())));
        verify(routeDistanceClient, times(1)).trip(anyList());
    }

    // (c1) Pemecahan perjalanan karena kapasitas berat (> 15 kg)
    @Test
    void pecahPerjalananSaatMelebihiKapasitasBerat() {
        Delivery d1 = buatDelivery("Muatan Berat 1", -7.76, 110.38, 10.0, 10.0);
        Delivery d2 = buatDelivery("Muatan Berat 2", -7.77, 110.39, 8.0, 10.0);

        List<UUID> ids = List.of(d1.getIdDelivery(), d2.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d1, d2));

        GeoJsonLineString geom = new GeoJsonLineString("LineString", List.of(List.of(110.37, -7.75)));
        when(routeDistanceClient.trip(anyList())).thenAnswer(inv -> {
            List<TitikKoordinat> titik = inv.getArgument(0);
            if (titik.size() == 3) {
                return Optional.of(new TripHasil(
                        List.of(0, 1, 2),
                        List.of(new TripLeg(2000, 0), new TripLeg(2000, 0), new TripLeg(3000, 0)),
                        geom
                ));
            }
            return Optional.of(new TripHasil(
                    List.of(0, 1),
                    List.of(new TripLeg(2000, 0), new TripLeg(2000, 0)),
                    geom
            ));
        });

        DeliveryRouteResponse res = planner.rencanakanRute(ids, SEKARANG);

        assertEquals(2, res.perjalanan().size());
        assertEquals(new BigDecimal("10.00"), res.perjalanan().get(0).beratMuatanKg());
        assertEquals(List.of(d1.getIdDelivery()),
                res.perjalanan().get(0).stops().stream().map(Stop::idDelivery).toList());
        assertEquals(new BigDecimal("8.00"), res.perjalanan().get(1).beratMuatanKg());
        assertEquals(List.of(d2.getIdDelivery()),
                res.perjalanan().get(1).stops().stream().map(Stop::idDelivery).toList());
    }

    // (c2) Pemecahan perjalanan karena kapasitas volume (> 40 liter)
    @Test
    void pecahPerjalananSaatMelebihiKapasitasVolume() {
        Delivery d1 = buatDelivery("Volume Besar 1", -7.76, 110.38, 3.0, 25.0);
        Delivery d2 = buatDelivery("Volume Besar 2", -7.77, 110.39, 3.0, 20.0);

        List<UUID> ids = List.of(d1.getIdDelivery(), d2.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d1, d2));

        GeoJsonLineString geom = new GeoJsonLineString("LineString", List.of(List.of(110.37, -7.75)));
        when(routeDistanceClient.trip(anyList())).thenAnswer(inv -> {
            List<TitikKoordinat> titik = inv.getArgument(0);
            if (titik.size() == 3) {
                return Optional.of(new TripHasil(
                        List.of(0, 1, 2),
                        List.of(new TripLeg(2000, 0), new TripLeg(2000, 0), new TripLeg(3000, 0)),
                        geom
                ));
            }
            return Optional.of(new TripHasil(
                    List.of(0, 1),
                    List.of(new TripLeg(2000, 0), new TripLeg(2000, 0)),
                    geom
            ));
        });

        DeliveryRouteResponse res = planner.rencanakanRute(ids, SEKARANG);

        assertEquals(2, res.perjalanan().size());
        assertEquals(new BigDecimal("25.00"), res.perjalanan().get(0).volumeMuatanLiter());
        assertEquals(new BigDecimal("20.00"), res.perjalanan().get(1).volumeMuatanLiter());
    }

    // (d) Fallback saat OSRM gagal -> nearest-neighbor Haversine x 1.3, sumberRute=PERKIRAAN, garisRute=null
    @Test
    void fallbackNearestNeighborSaatOsrmGagal() {
        // Toko di (-7.756318, 110.371033)
        Delivery dJauh = buatDelivery("Lebih Jauh", -7.792500, 110.365000, 2.0, 5.0);
        Delivery dDekat = buatDelivery("Lebih Dekat", -7.760000, 110.372000, 2.0, 5.0);

        List<UUID> ids = List.of(dJauh.getIdDelivery(), dDekat.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(dJauh, dDekat));
        when(routeDistanceClient.trip(anyList())).thenReturn(Optional.empty());

        DeliveryRouteResponse res = planner.rencanakanRute(ids, SEKARANG);

        assertEquals("PERKIRAAN", res.sumberRute());
        assertTrue(res.peringatan().contains(
                "Rute memakai estimasi garis lurus karena layanan peta tidak tersedia"));
        assertEquals(1, res.perjalanan().size());

        Perjalanan p = res.perjalanan().get(0);
        assertNull(p.garisRute());
        assertEquals(2, p.stops().size());
        // Nearest-neighbor memilih titik yang paling dekat toko lebih dulu (dDekat -> dJauh)
        assertEquals(dDekat.getIdDelivery(), p.stops().get(0).idDelivery());
        assertEquals(dJauh.getIdDelivery(), p.stops().get(1).idDelivery());

        BigDecimal expectedLeg1 = BigDecimal.valueOf(
                SmartDeliveryService.haversineKm(
                        props.getStore().getLatitude(), props.getStore().getLongitude(),
                        dDekat.getLatitudeTujuan(), dDekat.getLongitudeTujuan()) * 1.3
        ).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedLeg1, p.stops().get(0).jarakDariSebelumnyaKm());
    }

    // (e) Validasi input & status/pelaksana/koordinat/batas 8 titik/404
    @Test
    void validasiDaftarKosongDitolak() {
        assertThrows(InvalidDeliveryRouteException.class, () -> planner.rencanakanRute(List.of(), SEKARANG));
        assertThrows(InvalidDeliveryRouteException.class, () -> planner.rencanakanRute(null, SEKARANG));
    }

    @Test
    void validasiDuplikatDitolak() {
        UUID id = UUID.randomUUID();
        InvalidDeliveryRouteException ex = assertThrows(
                InvalidDeliveryRouteException.class,
                () -> planner.rencanakanRute(List.of(id, id), SEKARANG));
        assertTrue(ex.getMessage().toLowerCase().contains("duplikat"));
    }

    @Test
    void validasiLebihDari8TitikDitolak() {
        List<UUID> sembilan = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            sembilan.add(UUID.randomUUID());
        }
        InvalidDeliveryRouteException ex = assertThrows(
                InvalidDeliveryRouteException.class,
                () -> planner.rencanakanRute(sembilan, SEKARANG));
        assertTrue(ex.getMessage().contains("8"));
    }

    @Test
    void validasiPelaksanaBukanMotorTokoDitolak() {
        Delivery d = buatDelivery("Alamat Pihak Ketiga", -7.77, 110.37, 2.0, 5.0);
        d.setPelaksana("PIHAK_KETIGA");
        List<UUID> ids = List.of(d.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d));

        InvalidDeliveryRouteException ex = assertThrows(
                InvalidDeliveryRouteException.class,
                () -> planner.rencanakanRute(ids, SEKARANG));
        assertTrue(ex.getMessage().contains("MOTOR_TOKO"));
    }

    @Test
    void validasiStatusBukanMenungguPickupAtauDisiapkanDitolak() {
        Delivery d = buatDelivery("Sudah Terkirim", -7.77, 110.37, 2.0, 5.0);
        d.setStatusDelivery(DeliveryStatusMachine.TERKIRIM);
        List<UUID> ids = List.of(d.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d));

        InvalidDeliveryRouteException ex = assertThrows(
                InvalidDeliveryRouteException.class,
                () -> planner.rencanakanRute(ids, SEKARANG));
        assertTrue(ex.getMessage().contains("MENUNGGU_PICKUP"));
    }

    @Test
    void validasiStatusDisiapkanDiizinkan() {
        Delivery d = buatDelivery("Sedang Disiapkan", -7.765, 110.375, 2.0, 5.0);
        d.setStatusDelivery(DeliveryStatusMachine.DISIAPKAN);
        List<UUID> ids = List.of(d.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d));
        when(routeDistanceClient.trip(anyList())).thenReturn(Optional.empty());

        DeliveryRouteResponse res = planner.rencanakanRute(ids, SEKARANG);
        assertEquals(1, res.perjalanan().size());
        assertEquals(d.getIdDelivery(), res.perjalanan().get(0).stops().get(0).idDelivery());
    }

    @Test
    void validasiKoordinatKosongDitolakDanMenyebutkanIdDelivery() {
        Delivery d = buatDelivery("Tanpa Koordinat", null, null, 2.0, 5.0);
        List<UUID> ids = List.of(d.getIdDelivery());
        when(deliveryRepository.findAllById(ids)).thenReturn(List.of(d));

        InvalidDeliveryRouteException ex = assertThrows(
                InvalidDeliveryRouteException.class,
                () -> planner.rencanakanRute(ids, SEKARANG));
        assertTrue(ex.getMessage().contains(d.getIdDelivery().toString()));
        assertTrue(ex.getMessage().toLowerCase().contains("koordinat"));
    }

    @Test
    void validasiIdTidakDitemukanLemparEntityNotFoundException() {
        UUID idHilang = UUID.randomUUID();
        when(deliveryRepository.findAllById(List.of(idHilang))).thenReturn(List.of());

        EntityNotFoundException ex = assertThrows(
                EntityNotFoundException.class,
                () -> planner.rencanakanRute(List.of(idHilang), SEKARANG));
        assertTrue(ex.getMessage().contains(idHilang.toString()));
    }

    private Delivery buatDelivery(String alamat, Double lat, Double lon, double beratKg, double volumeLiter) {
        Product product = Product.builder()
                .idProduk("PRD-" + UUID.randomUUID().toString().substring(0, 6))
                .namaProduk("Produk Beku")
                .berat(BigDecimal.valueOf(beratKg))
                .volume(BigDecimal.valueOf(volumeLiter))
                .build();
        ProductBatch batch = ProductBatch.builder()
                .idProdBatch("B-" + UUID.randomUUID().toString().substring(0, 8))
                .product(product)
                .build();
        OrderItem item = OrderItem.builder()
                .idOrderItem(UUID.randomUUID())
                .productBatch(batch)
                .quantity(1)
                .build();
        Order order = Order.builder()
                .idOrder(UUID.randomUUID())
                .items(List.of(item))
                .build();
        return Delivery.builder()
                .idDelivery(UUID.randomUUID())
                .order(order)
                .alamatTujuan(alamat)
                .latitudeTujuan(lat)
                .longitudeTujuan(lon)
                .pelaksana("MOTOR_TOKO")
                .statusDelivery(DeliveryStatusMachine.MENUNGGU_PICKUP)
                .build();
    }
}
