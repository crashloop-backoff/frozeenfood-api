package com.lezzat.b2b2c.frozenfood.service.core;

import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.GarisRute;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Koordinat;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Perjalanan;
import com.lezzat.b2b2c.frozenfood.dto.response.DeliveryRouteResponse.Stop;
import com.lezzat.b2b2c.frozenfood.entity.Delivery;
import com.lezzat.b2b2c.frozenfood.entity.Order;
import com.lezzat.b2b2c.frozenfood.entity.OrderItem;
import com.lezzat.b2b2c.frozenfood.entity.Product;
import com.lezzat.b2b2c.frozenfood.exception.InvalidDeliveryRouteException;
import com.lezzat.b2b2c.frozenfood.repository.DeliveryRepository;
import com.lezzat.b2b2c.frozenfood.service.core.MuatanCalculator.ItemMuatan;
import com.lezzat.b2b2c.frozenfood.service.core.MuatanCalculator.Muatan;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.GeoJsonLineString;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.TitikKoordinat;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.TripHasil;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Perencana rute kurir Smart Delivery (motor toko).
 *
 * Mengurutkan beberapa pengiriman memakai OSRM Trip API (atau fallback nearest-neighbor
 * Haversine x faktor jalan bila OSRM mati/nonaktif), lalu memecah perjalanan bila
 * melanggar batas rantai dingin (menit) atau kapasitas motor (berat/volume).
 * Hanya menghitung, tidak mengubah data Delivery di database.
 */
@Service
@RequiredArgsConstructor
public class DeliveryRoutePlanner {

    static final int MAX_TITIK = 8;
    static final String PERINGATAN_FALLBACK =
            "Rute memakai estimasi garis lurus karena layanan peta tidak tersedia";

    private static final Set<String> STATUS_BOLEH = Set.of(
            DeliveryStatusMachine.MENUNGGU_PICKUP,
            DeliveryStatusMachine.DISIAPKAN
    );

    private final DeliveryRepository deliveryRepository;
    private final RouteDistanceClient routeDistanceClient;
    private final DeliveryProperties props;

    private record RencanaKandidat(
            List<Delivery> urutanStops,
            List<BigDecimal> jarakRuasKm, // ukuran = urutanStops.size() + 1 (terakhir = ruas pulang ke toko)
            GarisRute garisRute,
            boolean dariOsrm
    ) {
    }

    @Transactional(readOnly = true)
    public DeliveryRouteResponse rencanakanRute(List<UUID> idDeliveries) {
        ZonedDateTime sekarang = ZonedDateTime.now(ZoneId.of(props.getSchedule().getZone()));
        return rencanakanRute(idDeliveries, sekarang);
    }

    @Transactional(readOnly = true)
    public DeliveryRouteResponse rencanakanRute(List<UUID> idDeliveries, ZonedDateTime sekarang) {
        List<Delivery> deliveries = validasiDanAmbil(idDeliveries);

        DeliveryProperties.Store store = props.getStore();
        DeliveryProperties.Motor motor = props.getMotor();
        DeliveryProperties.Schedule schedule = props.getSchedule();
        DeliveryProperties.Unit unit = props.getUnit();

        if (motor.getAvgSpeedKmh() <= 0) {
            throw new IllegalArgumentException("Kecepatan rata-rata motor harus lebih dari 0");
        }

        ZoneId zone = ZoneId.of(schedule.getZone());
        LocalDateTime sekarangLokal = sekarang.withZoneSameInstant(zone)
                .toLocalDateTime()
                .truncatedTo(ChronoUnit.SECONDS);

        MuatanCalculator muatanCalc = new MuatanCalculator(unit.getWeightToKg(), unit.getVolumeToLiter());
        TitikKoordinat titikToko = new TitikKoordinat(store.getLatitude(), store.getLongitude());

        // Pool waktu siap armada motor (default 2 kurir -> perjalanan 1 & 2 bisa berangkat bersamaan).
        int jumlahKurir = Math.max(1, motor.getDrivers());
        PriorityQueue<LocalDateTime> jadwalSiapKurir = new PriorityQueue<>();
        for (int i = 0; i < jumlahKurir; i++) {
            jadwalSiapKurir.add(sekarangLokal);
        }

        List<Perjalanan> perjalananList = new ArrayList<>();
        Set<String> peringatan = new LinkedHashSet<>();

        List<Delivery> sisa = new ArrayList<>(deliveries);
        boolean osrmGagal = false;
        boolean semuaDariOsrm = true;

        // Loop iteratif: setiap iterasi pasti menyelesaikan >= 1 pengiriman, bebas rekursi tak terbatas.
        while (!sisa.isEmpty()) {
            List<Delivery> kandidat = new ArrayList<>(sisa);
            List<Delivery> ditunda = new ArrayList<>();

            RencanaKandidat rencanaFinal = null;
            Muatan muatanFinal = null;
            boolean melanggarColdChainTunggal = false;
            boolean melanggarMuatanTunggal = false;

            // Loop penyusutan kandidat: ukuran kandidat selalu berkurang ketat bila terjadi pemecahan.
            while (true) {
                RencanaKandidat rencana;
                if (!osrmGagal) {
                    Optional<RencanaKandidat> osrmPlan = hitungLewatOsrm(titikToko, kandidat);
                    if (osrmPlan.isPresent()) {
                        rencana = osrmPlan.get();
                    } else {
                        osrmGagal = true;
                        semuaDariOsrm = false;
                        peringatan.add(PERINGATAN_FALLBACK);
                        rencana = hitungFallbackNearestNeighbor(titikToko, kandidat);
                    }
                } else {
                    semuaDariOsrm = false;
                    rencana = hitungFallbackNearestNeighbor(titikToko, kandidat);
                }

                List<Delivery> urutan = rencana.urutanStops();
                List<ItemMuatan> akumulasiItems = new ArrayList<>();
                Muatan muatanTerakhir = null;
                int diterima = 0;
                int kumulatifMenit = 0;
                boolean tunggalColdChain = false;
                boolean tunggalMuatan = false;

                for (int i = 0; i < urutan.size(); i++) {
                    Delivery stopDelivery = urutan.get(i);
                    BigDecimal jarakRuas = rencana.jarakRuasKm().get(i);
                    int menitRuas = menitPerjalanan(jarakRuas, motor.getAvgSpeedKmh());
                    int menitTiba = kumulatifMenit + menitRuas;

                    List<ItemMuatan> itemKandidat = new ArrayList<>(akumulasiItems);
                    itemKandidat.addAll(itemsDariDelivery(stopDelivery));
                    Muatan muatanKandidat = muatanCalc.hitung(itemKandidat);

                    boolean langgarWaktu = menitTiba > motor.getMaxColdChainMinutes();
                    boolean langgarMuatan = !MuatanCalculator.muatDiSatuMotor(
                            muatanKandidat, motor.getMaxWeightKg(), motor.getMaxVolumeLiter());

                    if (i == 0) {
                        // Titik pertama pada perjalanan tetap dimasukkan agar tidak terjadi loop tak terbatas.
                        diterima = 1;
                        akumulasiItems = itemKandidat;
                        muatanTerakhir = muatanKandidat;
                        kumulatifMenit = menitTiba + motor.getStopMinutes();
                        tunggalColdChain = langgarWaktu;
                        tunggalMuatan = langgarMuatan;
                        if (langgarWaktu || langgarMuatan) {
                            break;
                        }
                    } else if (langgarWaktu || langgarMuatan) {
                        break;
                    } else {
                        diterima = i + 1;
                        akumulasiItems = itemKandidat;
                        muatanTerakhir = muatanKandidat;
                        kumulatifMenit = menitTiba + motor.getStopMinutes();
                    }
                }

                if (diterima == urutan.size()) {
                    rencanaFinal = rencana;
                    muatanFinal = muatanTerakhir;
                    melanggarColdChainTunggal = tunggalColdChain;
                    melanggarMuatanTunggal = tunggalMuatan;
                    break;
                }

                // Pecah: titik yang melanggar beserta sisanya dipindah ke perjalanan berikutnya;
                // perjalanan berjalan dihitung ulang lewat trip agar garisRute & ruas pulang akurat.
                List<Delivery> baruDitunda = new ArrayList<>(urutan.subList(diterima, urutan.size()));
                baruDitunda.addAll(ditunda);
                ditunda = baruDitunda;
                kandidat = new ArrayList<>(urutan.subList(0, diterima));
            }

            int perjalananKe = perjalananList.size() + 1;
            LocalDateTime waktuSiap = jadwalSiapKurir.poll();
            LocalDateTime berangkat = waktuSiap.plusMinutes(motor.getPrepMinutes());

            Perjalanan perjalanan = bangunPerjalanan(
                    perjalananKe, berangkat, rencanaFinal, muatanFinal,
                    melanggarColdChainTunggal, motor);
            perjalananList.add(perjalanan);
            jadwalSiapKurir.add(perjalanan.pulangPerkiraan());

            peringatan.addAll(muatanFinal.peringatan());
            if (melanggarColdChainTunggal) {
                Stop stopPertama = perjalanan.stops().get(0);
                peringatan.add("Perjalanan ke-" + perjalananKe + ": pengiriman " + stopPertama.idDelivery()
                        + " sendirian membutuhkan " + stopPertama.menitDariBerangkat()
                        + " menit sehingga melebihi batas rantai dingin "
                        + motor.getMaxColdChainMinutes() + " menit");
            }
            if (melanggarMuatanTunggal) {
                Stop stopPertama = perjalanan.stops().get(0);
                peringatan.add("Perjalanan ke-" + perjalananKe + ": muatan pengiriman " + stopPertama.idDelivery()
                        + " (" + muatanFinal.beratKg() + " kg / " + muatanFinal.volumeLiter()
                        + " liter) melebihi kapasitas satu motor ("
                        + motor.getMaxWeightKg() + " kg / " + motor.getMaxVolumeLiter() + " liter)");
            }

            sisa = ditunda;
        }

        if (perjalananList.size() > jumlahKurir) {
            peringatan.add("Jumlah perjalanan (" + perjalananList.size()
                    + ") melebihi jumlah armada motor toko (" + jumlahKurir
                    + "); perjalanan tambahan dijadwalkan menunggu kurir kembali");
        }

        String sumberRute = semuaDariOsrm ? "RUTE_ASLI" : "PERKIRAAN";
        return new DeliveryRouteResponse(
                new Koordinat(store.getLatitude(), store.getLongitude()),
                sumberRute,
                List.copyOf(perjalananList),
                List.copyOf(peringatan)
        );
    }

    private List<Delivery> validasiDanAmbil(List<UUID> idDeliveries) {
        if (idDeliveries == null || idDeliveries.isEmpty()) {
            throw new InvalidDeliveryRouteException("Daftar idDeliveries tidak boleh kosong");
        }
        Set<UUID> unik = new LinkedHashSet<>();
        List<UUID> duplikat = new ArrayList<>();
        for (UUID id : idDeliveries) {
            if (id == null) {
                throw new InvalidDeliveryRouteException("Daftar idDeliveries tidak boleh mengandung nilai kosong");
            }
            if (!unik.add(id)) {
                duplikat.add(id);
            }
        }
        if (!duplikat.isEmpty()) {
            throw new InvalidDeliveryRouteException("Daftar idDeliveries mengandung ID duplikat: " + duplikat);
        }
        if (idDeliveries.size() > MAX_TITIK) {
            throw new InvalidDeliveryRouteException(
                    "Maksimal " + MAX_TITIK + " titik pengiriman dalam satu permintaan rute (diterima: "
                            + idDeliveries.size() + ")");
        }

        List<Delivery> ditemukan = deliveryRepository.findAllById(idDeliveries);
        Map<UUID, Delivery> byId = new LinkedHashMap<>();
        for (Delivery d : ditemukan) {
            byId.put(d.getIdDelivery(), d);
        }

        List<UUID> tidakDitemukan = new ArrayList<>();
        for (UUID id : idDeliveries) {
            if (!byId.containsKey(id)) {
                tidakDitemukan.add(id);
            }
        }
        if (!tidakDitemukan.isEmpty()) {
            throw new EntityNotFoundException("Delivery tidak ditemukan untuk ID: " + tidakDitemukan);
        }

        List<Delivery> terurutInput = new ArrayList<>(idDeliveries.size());
        List<String> bukanMotorToko = new ArrayList<>();
        List<String> statusTidakValid = new ArrayList<>();
        List<UUID> tanpaKoordinat = new ArrayList<>();

        for (UUID id : idDeliveries) {
            Delivery d = byId.get(id);
            terurutInput.add(d);

            if (!"MOTOR_TOKO".equals(d.getPelaksana())) {
                bukanMotorToko.add(id + " (" + d.getPelaksana() + ")");
            }
            if (d.getStatusDelivery() == null || !STATUS_BOLEH.contains(d.getStatusDelivery())) {
                statusTidakValid.add(id + " (" + d.getStatusDelivery() + ")");
            }
            if (d.getLatitudeTujuan() == null || d.getLongitudeTujuan() == null
                    || !Double.isFinite(d.getLatitudeTujuan()) || !Double.isFinite(d.getLongitudeTujuan())) {
                tanpaKoordinat.add(id);
            }
        }

        if (!bukanMotorToko.isEmpty()) {
            throw new InvalidDeliveryRouteException(
                    "Semua pengiriman harus menggunakan pelaksana MOTOR_TOKO. Tidak valid: "
                            + String.join(", ", bukanMotorToko));
        }
        if (!statusTidakValid.isEmpty()) {
            throw new InvalidDeliveryRouteException(
                    "Semua pengiriman harus berstatus MENUNGGU_PICKUP atau DISIAPKAN. Tidak valid: "
                            + String.join(", ", statusTidakValid));
        }
        if (!tanpaKoordinat.isEmpty()) {
            throw new InvalidDeliveryRouteException(
                    "Pengiriman berikut belum memiliki koordinat tujuan: " + tanpaKoordinat);
        }

        return terurutInput;
    }

    private Optional<RencanaKandidat> hitungLewatOsrm(TitikKoordinat toko, List<Delivery> kandidat) {
        List<TitikKoordinat> titik = new ArrayList<>(kandidat.size() + 1);
        titik.add(toko);
        for (Delivery d : kandidat) {
            titik.add(new TitikKoordinat(d.getLatitudeTujuan(), d.getLongitudeTujuan()));
        }

        Optional<TripHasil> tripOpt = routeDistanceClient.trip(titik);
        if (tripOpt.isEmpty()) {
            return Optional.empty();
        }
        TripHasil trip = tripOpt.get();
        List<Integer> urutanIdx = trip.urutanInputIndex();
        if (urutanIdx.size() != titik.size() || trip.legs().size() != titik.size() || urutanIdx.get(0) != 0) {
            return Optional.empty();
        }

        List<Delivery> orderedDeliveries = new ArrayList<>(kandidat.size());
        for (int pos = 1; pos < urutanIdx.size(); pos++) {
            int inputIdx = urutanIdx.get(pos);
            if (inputIdx < 1 || inputIdx > kandidat.size()) {
                return Optional.empty();
            }
            orderedDeliveries.add(kandidat.get(inputIdx - 1));
        }

        List<BigDecimal> jarakRuasKm = trip.legs().stream()
                .map(leg -> BigDecimal.valueOf(leg.distanceMeters())
                        .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP))
                .toList();

        GeoJsonLineString geom = trip.geometry();
        GarisRute garisRute = geom == null ? null : new GarisRute(geom.type(), geom.coordinates());
        return Optional.of(new RencanaKandidat(orderedDeliveries, jarakRuasKm, garisRute, true));
    }

    private RencanaKandidat hitungFallbackNearestNeighbor(TitikKoordinat toko, List<Delivery> kandidat) {
        double faktor = props.getRoadDistanceFactor();
        List<Delivery> belumDikunjungi = new ArrayList<>(kandidat);
        List<Delivery> urutan = new ArrayList<>(kandidat.size());
        List<BigDecimal> jarakRuasKm = new ArrayList<>(kandidat.size() + 1);

        double curLat = toko.latitude();
        double curLon = toko.longitude();

        while (!belumDikunjungi.isEmpty()) {
            int bestIdx = 0;
            double bestDistKm = Double.POSITIVE_INFINITY;
            for (int i = 0; i < belumDikunjungi.size(); i++) {
                Delivery d = belumDikunjungi.get(i);
                double distKm = SmartDeliveryService.haversineKm(
                        curLat, curLon, d.getLatitudeTujuan(), d.getLongitudeTujuan()) * faktor;
                if (distKm < bestDistKm) {
                    bestDistKm = distKm;
                    bestIdx = i;
                }
            }
            Delivery dipilih = belumDikunjungi.remove(bestIdx);
            urutan.add(dipilih);
            jarakRuasKm.add(BigDecimal.valueOf(bestDistKm).setScale(2, RoundingMode.HALF_UP));
            curLat = dipilih.getLatitudeTujuan();
            curLon = dipilih.getLongitudeTujuan();
        }

        // Ruas pulang dari titik terakhir kembali ke toko
        double pulangKm = SmartDeliveryService.haversineKm(curLat, curLon, toko.latitude(), toko.longitude()) * faktor;
        jarakRuasKm.add(BigDecimal.valueOf(pulangKm).setScale(2, RoundingMode.HALF_UP));

        return new RencanaKandidat(urutan, jarakRuasKm, null, false);
    }

    private Perjalanan bangunPerjalanan(
            int perjalananKe,
            LocalDateTime berangkat,
            RencanaKandidat rencana,
            Muatan muatan,
            boolean melanggarRantaiDingin,
            DeliveryProperties.Motor motor
    ) {
        List<Delivery> urutan = rencana.urutanStops();
        List<Stop> stops = new ArrayList<>(urutan.size());
        BigDecimal totalJarak = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int kumulatifMenit = 0;

        for (int i = 0; i < urutan.size(); i++) {
            Delivery d = urutan.get(i);
            BigDecimal jarakRuas = rencana.jarakRuasKm().get(i);
            int menitRuas = menitPerjalanan(jarakRuas, motor.getAvgSpeedKmh());
            int menitTiba = kumulatifMenit + menitRuas;
            LocalDateTime estimasiTiba = berangkat.plusMinutes(menitTiba);

            UUID idOrder = d.getOrder() != null ? d.getOrder().getIdOrder() : null;
            stops.add(new Stop(
                    i + 1,
                    d.getIdDelivery(),
                    idOrder,
                    d.getAlamatTujuan(),
                    d.getLatitudeTujuan(),
                    d.getLongitudeTujuan(),
                    jarakRuas,
                    menitTiba,
                    estimasiTiba
            ));

            totalJarak = totalJarak.add(jarakRuas);
            kumulatifMenit = menitTiba + motor.getStopMinutes();
        }

        BigDecimal jarakPulang = rencana.jarakRuasKm().get(urutan.size());
        int menitPulang = menitPerjalanan(jarakPulang, motor.getAvgSpeedKmh());
        int totalMenit = kumulatifMenit + menitPulang;
        totalJarak = totalJarak.add(jarakPulang).setScale(2, RoundingMode.HALF_UP);
        LocalDateTime pulang = berangkat.plusMinutes(totalMenit);

        return new Perjalanan(
                perjalananKe,
                berangkat,
                pulang,
                totalJarak,
                totalMenit,
                muatan.beratKg(),
                muatan.volumeLiter(),
                melanggarRantaiDingin,
                rencana.garisRute(),
                List.copyOf(stops)
        );
    }

    /** Konsisten dengan DeliveryScheduler.menitPerjalanan: jarakKm / kecepatan * 60 dibulatkan ke atas. */
    private int menitPerjalanan(BigDecimal jarakKm, double kecepatanKmh) {
        return jarakKm.multiply(BigDecimal.valueOf(60))
                .divide(BigDecimal.valueOf(kecepatanKmh), 0, RoundingMode.CEILING)
                .intValueExact();
    }

    private List<ItemMuatan> itemsDariDelivery(Delivery d) {
        List<ItemMuatan> items = new ArrayList<>();
        Order order = d.getOrder();
        if (order == null || order.getItems() == null) {
            return items;
        }
        for (OrderItem it : order.getItems()) {
            if (it == null || it.getProductBatch() == null || it.getProductBatch().getProduct() == null) {
                continue;
            }
            Product p = it.getProductBatch().getProduct();
            int qty = it.getQuantity() == null ? 0 : it.getQuantity();
            items.add(new ItemMuatan(p.getNamaProduk(), p.getBerat(), p.getVolume(), qty));
        }
        return items;
    }
}
