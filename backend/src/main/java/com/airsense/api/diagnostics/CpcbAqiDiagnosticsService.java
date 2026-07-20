package com.airsense.api.diagnostics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CpcbAqiDiagnosticsService {
    private static final String RESOURCE_ID = "3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69";
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private final RestTemplateBuilder restTemplateBuilder;

    @Value("${cpcb.data-gov.api-key:${CPCB_DATA_GOV_API_KEY:${CPCB_DATA_GOV_IN_API_KEY:${DATA_GOV_IN_API_KEY:}}}}")
    private String apiKey;

    @Value("${cpcb.resource-id:${CPCB_RESOURCE_ID:${DATA_GOV_IN_CPCB_RESOURCE_ID:" + RESOURCE_ID + "}}}")
    private String resourceId;

    @Value("${cpcb.base-url:${DATA_GOV_IN_BASE_URL:https://api.data.gov.in/resource}}")
    private String baseUrl;

    @Value("${cpcb.freshness-minutes:${CPCB_FRESHNESS_MINUTES:120}}")
    private long freshnessMinutes;

    public Map<String, Object> diagnose(double lat, double lng, String city, String state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resource", Map.of(
                "name", "data.gov.in CPCB real-time AQI",
                "resourceId", resourceId,
                "url", baseUrl + "/" + resourceId,
                "filters", Map.of("city", city, "state", state)
        ));
        result.put("request", Map.of("lat", lat, "lng", lng, "city", city, "state", state));
        result.put("freshnessLimitMinutes", freshnessMinutes);

        if (apiKey == null || apiKey.isBlank()) {
            result.put("status", "BLOCKED");
            result.put("blocker", "CPCB_DATA_GOV_API_KEY is not configured on the backend.");
            result.put("rawRecords", List.of());
            result.put("stations", List.of());
            result.put("selectedStation", null);
            return result;
        }

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment(resourceId)
                .queryParam("api-key", apiKey)
                .queryParam("format", "json")
                .queryParam("limit", 1000)
                .queryParam("filters[city]", city)
                .queryParam("filters[state]", state)
                .build()
                .toUriString();

        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(8))
                .setReadTimeout(Duration.ofSeconds(20))
                .build();

        try {
            log.info("CPCB diagnostics fetching provider=data.gov.in resourceId={} city={} state={} lat={} lng={}",
                    resourceId, city, state, lat, lng);
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody() != null ? response.getBody() : Map.of();
            List<Map<String, Object>> records = records(body);
            List<Map<String, Object>> stations = stationSummaries(records, lat, lng);
            Optional<Map<String, Object>> selected = stations.stream()
                    .filter(station -> "LIVE".equals(station.get("freshnessStatus")))
                    .filter(station -> "VALID".equals(station.get("readingValidity")))
                    .filter(station -> station.get("distanceKm") instanceof Number)
                    .min(Comparator.comparingDouble(station -> ((Number) station.get("distanceKm")).doubleValue()));

            result.put("status", records.isEmpty() ? "UNAVAILABLE" : "SUCCESS");
            result.put("httpStatus", response.getStatusCode().value());
            result.put("schema", schema(records));
            result.put("schemaFindings", schemaFindings(records));
            result.put("rawRecords", records);
            result.put("rawRecordSample", records.stream().findFirst().orElse(Map.of()));
            result.put("stations", stations);
            result.put("selectedStation", selected.orElse(null));
            result.put("selectionExplanation", selected
                    .map(station -> "Selected nearest LIVE and VALID station with available coordinates.")
                    .orElse("No station could be selected because no fresh valid station with coordinates was present in the raw CPCB resource."));
            result.put("officialSourceComparison", selected
                    .map(station -> Map.of(
                            "source", "data.gov.in raw CPCB record",
                            "rawAqi", station.getOrDefault("aqi", "not_present"),
                            "observedTime", station.getOrDefault("lastUpdate", ""),
                            "comparison", "Diagnostic uses the unmodified official data.gov.in value. No transformation was applied."
                    ))
                    .orElse(Map.of("comparison", "Unavailable because no station was selected.")));
            return result;
        } catch (RestClientException e) {
            log.warn("CPCB diagnostics failed provider=data.gov.in resourceId={} city={} state={} reason={}",
                    resourceId, city, state, e.getMessage());
            result.put("status", "FAILED");
            result.put("blocker", e.getMessage());
            result.put("rawRecords", List.of());
            result.put("stations", List.of());
            result.put("selectedStation", null);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(Map<String, Object> body) {
        Object value = body.get("records");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<String, Object> record = (Map<String, Object>) item;
                    return (Map<String, Object>) new LinkedHashMap<>(record);
                })
                .toList();
    }

    private Map<String, Object> schema(List<Map<String, Object>> records) {
        Set<String> fields = records.stream()
                .flatMap(record -> record.keySet().stream())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("fields", fields);
        schema.put("recordShape", "Flat pollutant rows, typically one row per station and pollutant.");
        return schema;
    }

    private Map<String, Object> schemaFindings(List<Map<String, Object>> records) {
        Map<String, Object> sample = records.stream().findFirst().orElse(Map.of());
        Map<String, Object> findings = new LinkedHashMap<>();
        findings.put("stationNameField", firstPresent(sample, "station", "station_name", "stationName"));
        findings.put("coordinateFields", Map.of(
                "latitude", firstPresent(sample, "latitude", "lat"),
                "longitude", firstPresent(sample, "longitude", "lng", "lon")
        ));
        findings.put("aqiField", firstPresent(sample, "aqi", "AQI", "air_quality_index"));
        findings.put("pollutantConcentrationFields", List.of("pollutant_id", "pollutant_min/min_value", "pollutant_max/max_value", "pollutant_avg/avg_value"));
        findings.put("unitField", firstPresent(sample, "pollutant_unit", "unit", "units"));
        findings.put("lastUpdateField", firstPresent(sample, "last_update", "lastUpdate", "last_updated"));
        return findings;
    }

    private List<Map<String, Object>> stationSummaries(List<Map<String, Object>> records, double requestLat, double requestLng) {
        Map<String, List<Map<String, Object>>> grouped = records.stream()
                .collect(Collectors.groupingBy(record -> text(first(record, "station", "station_name", "stationName")),
                        LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> stations = new ArrayList<>();
        grouped.forEach((stationName, stationRecords) -> {
            Map<String, Object> first = stationRecords.get(0);
            Double lat = number(first(first, "latitude", "lat"));
            Double lng = number(first(first, "longitude", "lng", "lon"));
            Instant lastUpdate = stationRecords.stream()
                    .map(record -> parseTimestamp(text(first(record, "last_update", "lastUpdate", "last_updated"))))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            Object aqi = first(stationRecords.stream()
                    .map(record -> first(record, "aqi", "AQI", "air_quality_index"))
                    .filter(Objects::nonNull)
                    .toList());
            Map<String, Object> pollutants = stationRecords.stream()
                    .collect(Collectors.toMap(
                            record -> text(record.get("pollutant_id")),
                            record -> Map.of(
                                    "avg", valueOrUnavailable(first(record, "pollutant_avg", "avg_value")),
                                    "min", valueOrUnavailable(first(record, "pollutant_min", "min_value")),
                                    "max", valueOrUnavailable(first(record, "pollutant_max", "max_value")),
                                    "unit", valueOrUnavailable(record.get("pollutant_unit"))
                            ),
                            (a, b) -> b,
                            LinkedHashMap::new
                    ));
            OptionalIntWithPollutant derivedAqi = stationAqi(stationRecords);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("station", stationName);
            summary.put("coordinates", lat != null && lng != null ? Map.of("latitude", lat, "longitude", lng) : null);
            summary.put("distanceKm", lat != null && lng != null ? round(distanceKm(requestLat, requestLng, lat, lng)) : null);
            summary.put("aqi", aqi != null ? aqi : derivedAqi.aqi != null ? derivedAqi.aqi : "not_present");
            summary.put("aqiSourceType", aqi != null ? "STATION_OBSERVED" : derivedAqi.sourceType);
            summary.put("prominentPollutant", derivedAqi.pollutant);
            summary.put("pollutants", pollutants);
            summary.put("units", stationRecords.stream()
                    .map(record -> text(record.get("pollutant_unit")))
                    .filter(unit -> !unit.isBlank())
                    .distinct()
                    .toList());
            summary.put("lastUpdate", lastUpdate != null ? lastUpdate.toString() : "");
            summary.put("freshnessStatus", freshnessStatus(lastUpdate));
            summary.put("readingValidity", readingValidity(stationRecords));
            summary.put("rawRecordCount", stationRecords.size());
            stations.add(summary);
        });
        return stations.stream()
                .sorted(Comparator.comparing(station -> text(station.get("station"))))
                .toList();
    }

    private String freshnessStatus(Instant lastUpdate) {
        if (lastUpdate == null) return "UNAVAILABLE";
        Duration age = Duration.between(lastUpdate, Instant.now());
        if (age.isNegative()) return "INVALID";
        return age.toMinutes() <= freshnessMinutes ? "LIVE" : "STALE";
    }

    private String readingValidity(List<Map<String, Object>> records) {
        boolean hasAnyValue = records.stream().anyMatch(record ->
                isValidValue(record.get("aqi"))
                        || isValidValue(first(record, "pollutant_avg", "avg_value"))
                        || isValidValue(first(record, "pollutant_min", "min_value"))
                        || isValidValue(first(record, "pollutant_max", "max_value")));
        return hasAnyValue ? "VALID" : "INVALID";
    }

    private OptionalIntWithPollutant stationAqi(List<Map<String, Object>> records) {
        OptionalIntWithPollutant observed = records.stream()
                .map(record -> first(record, "aqi", "AQI", "air_quality_index"))
                .map(this::integer)
                .filter(Objects::nonNull)
                .findFirst()
                .map(aqi -> new OptionalIntWithPollutant(aqi, "STATION_OBSERVED", "AQI"))
                .orElse(null);
        if (observed != null) return observed;

        return records.stream()
                .map(record -> new OptionalIntWithPollutant(
                        naqiSubIndex(text(first(record, "pollutant_id", "pollutant", "pollutant_name")),
                                number(first(record, "pollutant_avg", "avg_value", "concentration"))),
                        "STATION_CALCULATED_FROM_CONCENTRATION",
                        text(first(record, "pollutant_id", "pollutant", "pollutant_name"))))
                .filter(value -> value.aqi != null)
                .max(Comparator.comparingInt(value -> value.aqi))
                .orElse(new OptionalIntWithPollutant(null, "UNAVAILABLE", ""));
    }

    private Integer naqiSubIndex(String pollutant, Double concentration) {
        if (concentration == null) return null;
        String p = pollutant.toUpperCase(Locale.ROOT).replace(".", "").replace("_", "");
        if (p.contains("PM25")) return subIndex(concentration, new double[]{0, 30, 60, 90, 120, 250, 500}, new int[]{0, 50, 100, 200, 300, 400, 500});
        if (p.contains("PM10")) return subIndex(concentration, new double[]{0, 50, 100, 250, 350, 430, 600}, new int[]{0, 50, 100, 200, 300, 400, 500});
        if (p.contains("NO2")) return subIndex(concentration, new double[]{0, 40, 80, 180, 280, 400, 800}, new int[]{0, 50, 100, 200, 300, 400, 500});
        if (p.contains("SO2")) return subIndex(concentration, new double[]{0, 40, 80, 380, 800, 1600, 2400}, new int[]{0, 50, 100, 200, 300, 400, 500});
        if (p.contains("O3")) return subIndex(concentration, new double[]{0, 50, 100, 168, 208, 748, 1000}, new int[]{0, 50, 100, 200, 300, 400, 500});
        if (p.equals("CO") || p.contains("CARBON")) return subIndex(concentration, new double[]{0, 1000, 2000, 10000, 17000, 34000, 50000}, new int[]{0, 50, 100, 200, 300, 400, 500});
        return null;
    }

    private Integer subIndex(double concentration, double[] concentrationBreakpoints, int[] indexBreakpoints) {
        if (concentration < 0) return null;
        for (int i = 1; i < concentrationBreakpoints.length; i++) {
            if (concentration <= concentrationBreakpoints[i]) {
                double cLow = concentrationBreakpoints[i - 1];
                double cHigh = concentrationBreakpoints[i];
                int iLow = indexBreakpoints[i - 1];
                int iHigh = indexBreakpoints[i];
                return (int) Math.round(((iHigh - iLow) / (cHigh - cLow)) * (concentration - cLow) + iLow);
            }
        }
        return 500;
    }

    private boolean isValidValue(Object value) {
        String text = text(value);
        return !text.isBlank() && !List.of("NA", "N/A", "NULL", "NONE", "-").contains(text.toUpperCase(Locale.ROOT));
    }

    private Optional<Instant> parseTimestamp(String value) {
        if (value.isBlank()) return Optional.empty();
        for (DateTimeFormatter formatter : TIMESTAMP_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(value, formatter).atZone(INDIA_ZONE).toInstant());
            } catch (DateTimeParseException ignored) {
                // Try the next observed CPCB timestamp format.
            }
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String firstPresent(Map<String, Object> sample, String... fields) {
        for (String field : fields) {
            if (sample.containsKey(field)) return field;
        }
        return "not_present";
    }

    private Object first(Map<String, Object> record, String... keys) {
        for (String key : keys) {
            if (record.containsKey(key)) return record.get(key);
        }
        return null;
    }

    private Object first(List<Object> values) {
        return values.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object valueOrUnavailable(Object value) {
        return isValidValue(value) ? value : "unavailable";
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer integer(Object value) {
        Double number = number(value);
        return number != null ? (int) Math.round(number) : null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record OptionalIntWithPollutant(Integer aqi, String sourceType, String pollutant) {}
}
