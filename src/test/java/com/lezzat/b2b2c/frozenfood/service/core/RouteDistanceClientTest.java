package com.lezzat.b2b2c.frozenfood.service.core;

import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.TitikKoordinat;
import com.lezzat.b2b2c.frozenfood.service.core.RouteDistanceClient.TripHasil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RouteDistanceClientTest {

    private static final String SAMPLE_OSRM_TRIP_JSON = """
            {
              "code": "Ok",
              "waypoints": [
                {
                  "waypoint_index": 0,
                  "trips_index": 0,
                  "distance": 12.3,
                  "name": "Jl. Kaliurang",
                  "location": [110.371033, -7.756318]
                },
                {
                  "waypoint_index": 2,
                  "trips_index": 0,
                  "distance": 4.1,
                  "name": "Jl. Malioboro",
                  "location": [110.365000, -7.792500]
                },
                {
                  "waypoint_index": 1,
                  "trips_index": 0,
                  "distance": 8.7,
                  "name": "Jl. Solo",
                  "location": [110.390000, -7.782000]
                }
              ],
              "trips": [
                {
                  "distance": 14500.5,
                  "duration": 1860.0,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [110.371033, -7.756318],
                      [110.390000, -7.782000],
                      [110.365000, -7.792500],
                      [110.371033, -7.756318]
                    ]
                  },
                  "legs": [
                    { "distance": 4200.0, "duration": 520.0, "steps": [] },
                    { "distance": 3800.5, "duration": 490.0, "steps": [] },
                    { "distance": 6500.0, "duration": 850.0, "steps": [] }
                  ]
                }
              ]
            }
            """;

    @Test
    void parseTrip_berhasilParseResponseOsrmStatis() {
        Optional<TripHasil> opt = RouteDistanceClient.parseTrip(SAMPLE_OSRM_TRIP_JSON);

        assertTrue(opt.isPresent());
        TripHasil hasil = opt.get();

        assertEquals(List.of(0, 2, 1), hasil.waypointIndices());
        assertEquals(List.of(0, 2, 1), hasil.urutanInputIndex());

        assertEquals(3, hasil.legs().size());
        assertEquals(4200.0, hasil.legs().get(0).distanceMeters(), 0.001);
        assertEquals(520.0, hasil.legs().get(0).durationSeconds(), 0.001);
        assertEquals(3800.5, hasil.legs().get(1).distanceMeters(), 0.001);
        assertEquals(6500.0, hasil.legs().get(2).distanceMeters(), 0.001);

        assertNotNull(hasil.geometry());
        assertEquals("LineString", hasil.geometry().type());
        assertEquals(4, hasil.geometry().coordinates().size());
        assertEquals(List.of(110.371033, -7.756318), hasil.geometry().coordinates().get(0));
        assertEquals(List.of(110.390000, -7.782000), hasil.geometry().coordinates().get(1));
    }

    @Test
    void parseTrip_urutanInputIndexMemetakanPosisiRuteDenganBenar() {
        // Input: 0(toko)->pos 0, 1(A)->pos 3, 2(B)->pos 1, 3(C)->pos 2
        // Maka urutan kunjungan di rute: [0, 2, 3, 1] (Toko -> B -> C -> A)
        String json = """
                {
                  "code": "Ok",
                  "waypoints": [
                    { "waypoint_index": 0 },
                    { "waypoint_index": 3 },
                    { "waypoint_index": 1 },
                    { "waypoint_index": 2 }
                  ],
                  "trips": [
                    {
                      "geometry": { "type": "LineString", "coordinates": [[110.37, -7.75], [110.38, -7.76]] },
                      "legs": [
                        { "distance": 1000.0, "duration": 100.0 },
                        { "distance": 2000.0, "duration": 200.0 },
                        { "distance": 3000.0, "duration": 300.0 },
                        { "distance": 4000.0, "duration": 400.0 }
                      ]
                    }
                  ]
                }
                """;

        TripHasil hasil = RouteDistanceClient.parseTrip(json).orElseThrow();
        assertEquals(List.of(0, 3, 1, 2), hasil.waypointIndices());
        assertEquals(List.of(0, 2, 3, 1), hasil.urutanInputIndex());
    }

    @Test
    void parseTrip_kodeBukanOkAtauJsonRusakMengembalikanEmpty() {
        assertTrue(RouteDistanceClient.parseTrip("{\"code\":\"NoTrips\",\"waypoints\":[],\"trips\":[]}").isEmpty());
        assertTrue(RouteDistanceClient.parseTrip("{bukan-json").isEmpty());
        assertTrue(RouteDistanceClient.parseTrip(null).isEmpty());
        assertTrue(RouteDistanceClient.parseTrip("").isEmpty());
    }

    @Test
    void parseTrip_jumlahLegsTidakCocokMengembalikanEmpty() {
        String invalidLegsJson = """
                {
                  "code": "Ok",
                  "waypoints": [
                    { "waypoint_index": 0 },
                    { "waypoint_index": 1 }
                  ],
                  "trips": [
                    {
                      "geometry": { "type": "LineString", "coordinates": [[110.37, -7.75], [110.38, -7.76]] },
                      "legs": [
                        { "distance": 1000.0, "duration": 100.0 }
                      ]
                    }
                  ]
                }
                """;
        assertTrue(RouteDistanceClient.parseTrip(invalidLegsJson).isEmpty());
    }

    @Test
    void parseJarakKm_tetapBerfungsiDenganPembulatanHalfUp() {
        String routeJson = "{\"code\":\"Ok\",\"routes\":[{\"distance\":5435.0,\"duration\":600.0}]}";
        assertEquals(Optional.of(new BigDecimal("5.44")), RouteDistanceClient.parseJarakKm(routeJson));
        assertTrue(RouteDistanceClient.parseJarakKm("{\"code\":\"NoRoute\"}").isEmpty());
    }

    @Test
    void tripDanJarakKm_saatDisabledLangsungEmptyTanpaPanggilJaringan() {
        DeliveryProperties props = new DeliveryProperties();
        props.getRouting().setEnabled(false);
        RouteDistanceClient client = new RouteDistanceClient(props);

        assertTrue(client.jarakKm(-7.75, 110.37, -7.79, 110.36).isEmpty());
        assertTrue(client.trip(List.of(
                new TitikKoordinat(-7.756318, 110.371033),
                new TitikKoordinat(-7.792500, 110.365000)
        )).isEmpty());
    }

    @Test
    void trip_mengirimFormatUrlOsrmDanHeaderUserAgentYangBenar() throws Exception {
        AtomicReference<String> capturedUri = new AtomicReference<>();
        AtomicReference<String> capturedUserAgent = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedUri.set(exchange.getRequestURI().toString());
            capturedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = SAMPLE_OSRM_TRIP_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            DeliveryProperties props = new DeliveryProperties();
            props.getRouting().setEnabled(true);
            props.getRouting().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.getRouting().setTimeoutMs(3000);

            RouteDistanceClient client = new RouteDistanceClient(props);
            Optional<TripHasil> result = client.trip(List.of(
                    new TitikKoordinat(-7.756318, 110.371033),
                    new TitikKoordinat(-7.792500, 110.365000),
                    new TitikKoordinat(-7.782000, 110.390000)
            ));

            assertTrue(result.isPresent());
            assertEquals("LezzatFrozenFood/1.0 (tugas kuliah)", capturedUserAgent.get());
            assertEquals(
                    "/trip/v1/driving/110.371033,-7.756318;110.365000,-7.792500;110.390000,-7.782000"
                            + "?source=first&roundtrip=true&geometries=geojson&overview=full&steps=false",
                    capturedUri.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void trip_saatServerErrorMengembalikanEmpty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            DeliveryProperties props = new DeliveryProperties();
            props.getRouting().setEnabled(true);
            props.getRouting().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.getRouting().setTimeoutMs(3000);

            RouteDistanceClient client = new RouteDistanceClient(props);
            Optional<TripHasil> result = client.trip(List.of(
                    new TitikKoordinat(-7.756318, 110.371033),
                    new TitikKoordinat(-7.792500, 110.365000)
            ));

            assertTrue(result.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void smartDeliveryService_fallbackKeHaversineSaatOsrmGagalAtauNonaktif() {
        DeliveryProperties props = new DeliveryProperties();
        props.getRouting().setEnabled(false);
        RouteDistanceClient client = new RouteDistanceClient(props);
        SmartDeliveryService smart = new SmartDeliveryService(client);
        org.springframework.test.util.ReflectionTestUtils.setField(smart, "warehouseLatitude", -7.756318);
        org.springframework.test.util.ReflectionTestUtils.setField(smart, "warehouseLongitude", 110.371033);
        org.springframework.test.util.ReflectionTestUtils.setField(smart, "roadDistanceFactor", 1.3);

        SmartDeliveryService.JarakHasil hasil = smart.hitungJarak(-7.792500, 110.365000);
        assertEquals("PERKIRAAN", hasil.sumber());
        assertEquals(smart.hitungJarakKm(-7.792500, 110.365000), hasil.jarakKm());
        assertTrue(hasil.jarakKm().compareTo(BigDecimal.ZERO) > 0);
    }
}
