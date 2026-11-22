package com.lezzat.b2b2c.frozenfood.service.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Klien jarak & rute jalan ASLI lewat OSRM (Ide 5: hybrid + Rute Kurir). Default NONAKTIF.
 * Gagal / lambat / respons aneh -> Optional.empty(), dan pemanggil otomatis memakai
 * fallback perkiraan Haversine x faktor jalan.
 */
@Component
public class RouteDistanceClient {

    private static final Logger log = LoggerFactory.getLogger(RouteDistanceClient.class);
    private static final Pattern DISTANCE = Pattern.compile("\"routes\"\\s*:\\s*\\[\\s*\\{.*?\"distance\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)", Pattern.DOTALL);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DeliveryProperties props;
    private final HttpClient http;

    public record TitikKoordinat(double latitude, double longitude) {
    }

    public record TripLeg(double distanceMeters, double durationSeconds) {
    }

    public record GeoJsonLineString(String type, List<List<Double>> coordinates) {
    }

    public record TripHasil(
            List<Integer> waypointIndices,
            List<TripLeg> legs,
            GeoJsonLineString geometry
    ) {
        /**
         * Mengembalikan daftar indeks titik input sesuai urutan kunjungan pada rute.
         * Contoh: waypoints[0].waypoint_index=0, waypoints[1].waypoint_index=2,
         * waypoints[2].waypoint_index=1 -> urutanInputIndex() = [0, 2, 1].
         */
        public List<Integer> urutanInputIndex() {
            int n = waypointIndices.size();
            Integer[] ordered = new Integer[n];
            for (int inputIdx = 0; inputIdx < n; inputIdx++) {
                ordered[waypointIndices.get(inputIdx)] = inputIdx;
            }
            return List.of(ordered);
        }
    }

    public RouteDistanceClient(DeliveryProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getRouting().getTimeoutMs()))
                .build();
    }

    public Optional<BigDecimal> jarakKm(double asalLat, double asalLon, double tujuLat, double tujuLon) {
        if (!props.getRouting().isEnabled()) {
            return Optional.empty();
        }
        try {
            String baseUrl = normalizedBaseUrl();
            String url = String.format(Locale.ROOT, "%s/route/v1/driving/%f,%f;%f,%f?overview=false",
                    baseUrl, asalLon, asalLat, tujuLon, tujuLat);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", props.getRouting().getUserAgent())
                    .timeout(Duration.ofMillis(props.getRouting().getTimeoutMs()))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("OSRM balas status {}, pakai perkiraan", res.statusCode());
                return Optional.empty();
            }
            return parseJarakKm(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("OSRM gagal ({}), pakai perkiraan", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Memanggil OSRM Trip API untuk mengurutkan titik pengantaran pulang-pergi (roundtrip).
     * Titik pertama (index 0) wajib berupa koordinat toko; urutan koordinat ke OSRM = lng,lat.
     */
    public Optional<TripHasil> trip(List<TitikKoordinat> titik) {
        if (!props.getRouting().isEnabled() || titik == null || titik.size() < 2) {
            return Optional.empty();
        }
        try {
            String coords = titik.stream()
                    .map(t -> String.format(Locale.ROOT, "%f,%f", t.longitude(), t.latitude()))
                    .collect(Collectors.joining(";"));
            String url = normalizedBaseUrl()
                    + "/trip/v1/driving/" + coords
                    + "?source=first&roundtrip=true&geometries=geojson&overview=full&steps=false";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", props.getRouting().getUserAgent())
                    .timeout(Duration.ofMillis(props.getRouting().getTimeoutMs()))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("OSRM trip balas status {}, pakai fallback", res.statusCode());
                return Optional.empty();
            }
            return parseTrip(res.body())
                    .filter(hasil -> hasil.waypointIndices().size() == titik.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("OSRM trip gagal ({}), pakai fallback", e.toString());
            return Optional.empty();
        }
    }

    private String normalizedBaseUrl() {
        String base = props.getRouting().getBaseUrl();
        return base == null ? "" : base.replaceAll("/+$", "");
    }

    /** Dipisah supaya bisa diuji tanpa jaringan. OSRM memberi jarak dalam meter. */
    static Optional<BigDecimal> parseJarakKm(String json) {
        if (json == null) {
            return Optional.empty();
        }
        Matcher m = DISTANCE.matcher(json);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(m.group(1))
                .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP));
    }

    /** Dipisah supaya bisa diuji dengan respons JSON OSRM statis tanpa jaringan. */
    static Optional<TripHasil> parseTrip(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode codeNode = root.get("code");
            if (codeNode != null && !"Ok".equals(codeNode.asString())) {
                log.warn("OSRM trip code bukan Ok: {}", codeNode.asString());
                return Optional.empty();
            }

            JsonNode waypointsNode = root.get("waypoints");
            if (waypointsNode == null || !waypointsNode.isArray() || waypointsNode.size() < 2) {
                return Optional.empty();
            }
            int n = waypointsNode.size();
            List<Integer> waypointIndices = new ArrayList<>(n);
            boolean[] seen = new boolean[n];
            for (int i = 0; i < n; i++) {
                JsonNode wp = waypointsNode.get(i);
                JsonNode idxNode = wp == null ? null : wp.get("waypoint_index");
                if (idxNode == null || !idxNode.isIntegralNumber()) {
                    return Optional.empty();
                }
                int idx = idxNode.intValue();
                if (idx < 0 || idx >= n || seen[idx]) {
                    return Optional.empty();
                }
                seen[idx] = true;
                waypointIndices.add(idx);
            }
            if (waypointIndices.get(0) != 0) {
                return Optional.empty();
            }

            JsonNode tripsNode = root.get("trips");
            if (tripsNode == null || !tripsNode.isArray() || tripsNode.isEmpty()) {
                return Optional.empty();
            }
            JsonNode trip0 = tripsNode.get(0);
            JsonNode legsNode = trip0 == null ? null : trip0.get("legs");
            if (legsNode == null || !legsNode.isArray() || legsNode.size() != n) {
                return Optional.empty();
            }
            List<TripLeg> legs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                JsonNode leg = legsNode.get(i);
                JsonNode distNode = leg == null ? null : leg.get("distance");
                JsonNode durNode = leg == null ? null : leg.get("duration");
                if (distNode == null || !distNode.isNumber() || durNode == null || !durNode.isNumber()) {
                    return Optional.empty();
                }
                double dist = distNode.doubleValue();
                double dur = durNode.doubleValue();
                if (dist < 0 || dur < 0 || !Double.isFinite(dist) || !Double.isFinite(dur)) {
                    return Optional.empty();
                }
                legs.add(new TripLeg(dist, dur));
            }

            JsonNode geomNode = trip0.get("geometry");
            if (geomNode == null || !geomNode.isObject()) {
                return Optional.empty();
            }
            JsonNode typeNode = geomNode.get("type");
            JsonNode coordsNode = geomNode.get("coordinates");
            if (typeNode == null || !"LineString".equals(typeNode.asString())
                    || coordsNode == null || !coordsNode.isArray()) {
                return Optional.empty();
            }
            List<List<Double>> coordinates = new ArrayList<>(coordsNode.size());
            for (JsonNode point : coordsNode) {
                if (point == null || !point.isArray() || point.size() < 2
                        || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                    return Optional.empty();
                }
                coordinates.add(List.of(point.get(0).doubleValue(), point.get(1).doubleValue()));
            }
            GeoJsonLineString geometry = new GeoJsonLineString("LineString", List.copyOf(coordinates));

            return Optional.of(new TripHasil(List.copyOf(waypointIndices), List.copyOf(legs), geometry));
        } catch (Exception e) {
            log.warn("Gagal parse respons OSRM trip: {}", e.toString());
            return Optional.empty();
        }
    }
}
