package com.airsense.api.fusion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

class CpcbAqiServicePaginationTest {

    @Test
    void allStationFetchReadsEveryProviderPage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/resource/resource-id", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body = query != null && query.contains("offset=1000")
                    ? page("Beta Station", 3, 1003)
                    : page("Alpha Station", 1000, 1003);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        CpcbAqiService service = new CpcbAqiService(new RestTemplateBuilder(), null);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:" + server.getAddress().getPort() + "/resource");
        ReflectionTestUtils.setField(service, "resourceId", "resource-id");
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 10L);

        try {
            CpcbAqiService.AllStationFetchResult result = service.fetchAllStationObservations();

            assertThat(result.providerTotalRecords()).isEqualTo(1003);
            assertThat(result.pageCount()).isEqualTo(2);
            assertThat(result.stations()).extracting(station -> station.get("station"))
                    .contains("Alpha Station", "Beta Station");
        } finally {
            server.stop(0);
        }
    }

    private String page(String station, int records, int total) {
        StringBuilder builder = new StringBuilder("{\"total\":").append(total).append(",\"records\":[");
        String[] pollutants = {"PM2.5", "PM10", "NO2", "SO2"};
        for (int i = 0; i < records; i++) {
            if (i > 0) builder.append(',');
            String pollutant = pollutants[i % pollutants.length];
            builder.append("{\"station\":\"").append(station)
                    .append("\",\"city\":\"Delhi\",\"state\":\"Delhi\",\"latitude\":\"28.61\",\"longitude\":\"77.23\",")
                    .append("\"last_update\":\"18-07-2026 01:00:00\",\"pollutant_id\":\"").append(pollutant)
                    .append("\",\"avg_value\":\"").append(i % 4 == 0 ? "80" : "40").append("\"}");
        }
        return builder.append("]}").toString();
    }
}
