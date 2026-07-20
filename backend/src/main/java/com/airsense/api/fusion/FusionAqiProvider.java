package com.airsense.api.fusion;

import com.airsense.api.entities.CityMetricsSnapshot;
import com.airsense.api.entities.SensorData;
import com.airsense.api.repositories.CityMetricsSnapshotRepository;
import com.airsense.api.services.OpenWeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FusionAqiProvider implements FusionDataProvider {
    private final FusionCityResolver cityResolver;
    private final CityMetricsSnapshotRepository snapshotRepository;
    private final OpenWeatherService openWeatherService;
    private final PrimaryAqiSelectionService primaryAqiSelectionService;

    @Override
    public String getProviderKey() {
        return "aqi";
    }

    @Override
    public String getProviderName() {
        return "CPCB-first Current AQI";
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        List<SensorData> recent = cityResolver.recentSensorData(request.getCityId(), 48);
        List<SensorData> latestPerStation = latestPerStation(recent);
        Map<String, Object> data = new HashMap<>();
        data.put("stationCount", 0);
        data.put("stations", List.of());
        data.put("currentAqi", null);
        data.put("canonicalAqi", null);
        data.put("dominantPollutant", "PM2.5");
        data.put("latestTimestamp", latestPerStation.stream()
                .map(SensorData::getTimestamp)
                .filter(ts -> ts != null)
                .max(Comparator.naturalOrder())
                .map(Instant::toString)
                .orElse(""));
        data.put("historicalAQI", historicalAqi(request.getCityId()));
        data.put("dataSource", "unavailable");

        if (request.getLatitude() != null && request.getLongitude() != null) {
            Map<String, Object> openWeatherAqi = openWeatherAqi(request, data);
            Map<String, Object> primary = primaryAqiSelectionService.select(request, request.getLatitude(), request.getLongitude(),
                    openWeatherAqi, data.get("historicalAQI"));
            if (Boolean.TRUE.equals(primary.get("available"))) {
                return FusionProviderResponse.success(getProviderKey(), primary, confidence(primary));
            }
            return unavailableResponse(primary, String.valueOf(primary.getOrDefault("reason", "Current AQI unavailable")));
        }

        data.put("available", false);
        data.put("aqiStandard", "");
        data.put("aqiCategory", "UNAVAILABLE");
        data.put("sourceType", "UNAVAILABLE");
        data.put("sourceLabel", "Current AQI unavailable");
        data.put("provider", "UNAVAILABLE");
        data.put("providerStatus", "UNAVAILABLE");
        data.put("reason", "Selected latitude/longitude is required for current AQI provider selection.");
        return unavailableResponse(data, String.valueOf(data.get("reason")));
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setAqi(response.getData());
        context.setHistoricalAQI(listOfMaps(response.getData().get("historicalAQI")));
    }

    private List<SensorData> latestPerStation(List<SensorData> recent) {
        return recent.stream()
                .filter(data -> data.getSensorId() != null)
                .collect(Collectors.groupingBy(SensorData::getSensorId,
                        Collectors.maxBy(Comparator.comparing(SensorData::getTimestamp))))
                .values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Map<String, Object> stationSummary(SensorData data) {
        Map<String, Object> station = new LinkedHashMap<>();
        station.put("sensorId", data.getSensorId());
        station.put("stationName", data.getStationName());
        station.put("wardId", data.getWardId());
        station.put("timestamp", data.getTimestamp() != null ? data.getTimestamp().toString() : "");
        station.put("coordinates", coordinates(data));
        station.put("pollutants", pollutants(data));
        return station;
    }

    private Map<String, Object> pollutants(SensorData data) {
        if (data.getPollutants() == null) {
            return Map.of("aqi", 0, "pm25", 0.0, "pm10", 0.0, "no2", 0.0, "so2", 0.0, "co", 0.0, "o3", 0.0);
        }
        SensorData.Pollutants p = data.getPollutants();
        return Map.of(
                "aqi", p.getAqi() != null ? p.getAqi() : 0,
                "pm25", p.getPm25() != null ? p.getPm25() : 0.0,
                "pm10", p.getPm10() != null ? p.getPm10() : 0.0,
                "no2", p.getNo2() != null ? p.getNo2() : 0.0,
                "so2", p.getSo2() != null ? p.getSo2() : 0.0,
                "co", p.getCo() != null ? p.getCo() : 0.0,
                "o3", p.getO3() != null ? p.getO3() : 0.0
        );
    }

    private Map<String, Object> openWeatherAqi(FusionRequest request, Map<String, Object> baseData) {
        double latitude = cityResolver.latitude(request);
        double longitude = cityResolver.longitude(request);
        Map<String, Object> air = openWeatherService.getLiveAirQuality(latitude, longitude);
        if (!Boolean.TRUE.equals(air.get("available"))) {
            return air;
        }

        Map<String, Object> pollutants = Map.of(
                "aqi", numberOrZero(air.get("aqi")),
                "openWeatherAqiIndex", numberOrZero(air.get("openWeatherAqiIndex")),
                "calculatedAqi", numberOrZero(air.get("calculatedAqi")),
                "pm25", numberOrZero(air.get("pm25")),
                "pm10", numberOrZero(air.get("pm10")),
                "no2", numberOrZero(air.get("no2")),
                "so2", numberOrZero(air.get("so2")),
                "co", numberOrZero(air.get("co")),
                "o3", numberOrZero(air.get("o3"))
        );

        Map<String, Object> station = new LinkedHashMap<>();
        station.put("sensorId", "openweather-air-pollution");
        station.put("stationName", "OpenWeather Air Pollution");
        station.put("wardId", String.valueOf(request.getParameters().getOrDefault("wardId", "")));
        station.put("timestamp", String.valueOf(air.getOrDefault("timestamp", "")));
        station.put("coordinates", Map.of("latitude", latitude, "longitude", longitude));
        station.put("pollutants", pollutants);

        Map<String, Object> data = new HashMap<>();
        data.put("stationCount", 1);
        data.put("stations", List.of(station));
        data.put("currentAqi", null);
        data.put("openWeatherAqiIndex", numberOrZero(air.get("openWeatherAqiIndex")).intValue());
        data.put("openWeatherAqiScale", "OPENWEATHER_1_TO_5");
        data.put("openWeatherAqiCategory", String.valueOf(air.getOrDefault("openWeatherAqiCategory", "Unavailable")));
        data.put("calculatedAqi", air.get("calculatedAqi"));
        data.put("dominantPollutant", dominantPollutant(air));
        data.put("latestTimestamp", String.valueOf(air.getOrDefault("timestamp", "")));
        data.put("lastUpdated", String.valueOf(air.getOrDefault("lastUpdated", air.getOrDefault("timestamp", ""))));
        data.put("historicalAQI", baseData.get("historicalAQI"));
        data.put("dataSource", "openweather_air_pollution");
        data.put("provider", "OpenWeather");
        data.put("fallbackUsed", air.getOrDefault("fallback", false));
        data.put("httpStatus", air.get("httpStatus"));
        return data;
    }

    private Map<String, Object> primaryIqAirAqi(Map<String, Object> iqAir, Map<String, Object> cpcb, Map<String, Object> openWeather,
                                                Map<String, Object> baseData) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.putAll(iqAir);
        data.put("openWeather", openWeather);
        data.put("openWeatherAqiIndex", openWeather.get("openWeatherAqiIndex"));
        data.put("openWeatherAqiCategory", openWeather.getOrDefault("openWeatherAqiCategory", "Unavailable"));
        data.put("openWeatherProvider", "OpenWeather");
        data.put("cpcbEvidence", cpcb);
        data.put("cpcbProvider", "CPCB_CAAQMS");
        data.put("cpcbPollutants", pollutantsFromCpcb(cpcb));
        data.put("cpcbDiagnostics", cpcb);
        data.put("historicalAQI", baseData.get("historicalAQI"));
        data.put("pollutants", pollutantMapFromCpcb(cpcb));
        data.put("pollutantBreakdown", pollutantsFromCpcb(cpcb));
        data.put("pollutantsSource", "CPCB_CAAQMS_SECONDARY_EVIDENCE");

        if (Boolean.TRUE.equals(iqAir.get("available"))) {
            data.put("dataSource", "iqair_airvisual_nearest_city");
            data.put("stationCount", 1);
            data.put("stations", List.of(Map.of(
                    "stationName", iqAir.getOrDefault("stationName", "IQAir AirVisual nearest city"),
                    "coordinates", iqAir.getOrDefault("coordinates", Map.of()),
                    "providerCoordinates", iqAir.getOrDefault("providerCoordinates", Map.of()),
                    "pollutants", Map.of(),
                    "timestamp", iqAir.getOrDefault("observedAt", "")
            )));
            return data;
        }

        data.put("available", false);
        data.put("currentAqi", null);
        data.put("canonicalAqi", null);
        data.put("aqi", null);
        data.put("aqiCategory", "UNAVAILABLE");
        data.put("dataSource", "unavailable");
        data.put("sourceType", "UNAVAILABLE");
        data.put("sourceLabel", "IQAir AirVisual US AQI unavailable");
        data.put("provider", "IQAir AirVisual");
        data.put("providerStatus", "UNAVAILABLE");
        data.put("freshnessStatus", "UNAVAILABLE");
        data.put("stationCount", 0);
        data.put("stations", List.of());
        return data;
    }

    private FusionProviderResponse unavailableResponse(Map<String, Object> data, String reason) {
        return FusionProviderResponse.builder()
                .providerKey(getProviderKey())
                .providerName(getProviderName())
                .status(FusionStatus.FAILED)
                .providerStatus(FusionStatus.FAILED.name())
                .confidence(0.0)
                .providerConfidence(0.0)
                .data(data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>())
                .errors(List.of(reason != null && !reason.isBlank() ? reason : "Current AQI unavailable"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pollutantsFromCpcb(Map<String, Object> cpcb) {
        Object value = cpcb.get("pollutants");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private Map<String, Object> pollutantMapFromCpcb(Map<String, Object> cpcb) {
        Map<String, Object> pollutants = new LinkedHashMap<>();
        for (Map<String, Object> row : pollutantsFromCpcb(cpcb)) {
            String key = String.valueOf(row.getOrDefault("pollutant", "")).toLowerCase().replace(".", "");
            if (key.equals("pm25")) key = "pm25";
            if (key.equals("pm10")) key = "pm10";
            if (!key.isBlank()) {
                pollutants.put(key, row.getOrDefault("avg", row.getOrDefault("value", "")));
            }
        }
        return pollutants;
    }

    private List<Map<String, Object>> pollutantsFromOpenWeather(Map<String, Object> air) {
        return List.of("pm25", "pm10", "no2", "so2", "o3", "co").stream()
                .map(key -> {
                    Map<String, Object> pollutant = new LinkedHashMap<>();
                    pollutant.put("pollutant", key.toUpperCase());
                    pollutant.put("avg", air.get(key) != null ? air.get(key) : 0);
                    pollutant.put("unit", "OpenWeather units");
                    return pollutant;
                })
                .toList();
    }

    private double confidence(Map<String, Object> canonical) {
        String provider = String.valueOf(canonical.getOrDefault("provider", ""));
        String freshness = String.valueOf(canonical.getOrDefault("freshnessStatus", ""));
        if ("CPCB_CAAQMS".equals(provider) && "LIVE".equals(freshness)) return 0.94;
        if ("IQAIR".equals(provider) && "LIVE".equals(freshness)) return 0.76;
        return 0.0;
    }

    private Number numberOrZero(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private String dominantPollutant(Map<String, Object> air) {
        return Map.of(
                        "PM2.5", numberOrZero(air.get("pm25")).doubleValue(),
                        "PM10", numberOrZero(air.get("pm10")).doubleValue(),
                        "NO2", numberOrZero(air.get("no2")).doubleValue(),
                        "SO2", numberOrZero(air.get("so2")).doubleValue(),
                        "CO", numberOrZero(air.get("co")).doubleValue(),
                        "O3", numberOrZero(air.get("o3")).doubleValue()
                ).entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("PM2.5");
    }

    private Map<String, Object> coordinates(SensorData data) {
        if (data.getCoordinates() == null || data.getCoordinates().getCoordinates() == null
                || data.getCoordinates().getCoordinates().size() < 2) {
            return Map.of();
        }
        return Map.of(
                "longitude", data.getCoordinates().getCoordinates().get(0),
                "latitude", data.getCoordinates().getCoordinates().get(1)
        );
    }

    private List<Map<String, Object>> historicalAqi(String cityId) {
        List<Map<String, Object>> snapshots = snapshotRepository.findByCityIdOrderByTimestampDesc(cityId).stream()
                .limit(30)
                .map(this::snapshotSummary)
                .toList();
        if (!snapshots.isEmpty()) {
            return snapshots;
        }
        return cityResolver.recentSensorData(cityId, 24 * 30L).stream()
                .sorted(Comparator.comparing(SensorData::getTimestamp).reversed())
                .limit(30)
                .map(data -> Map.<String, Object>of(
                        "timestamp", data.getTimestamp() != null ? data.getTimestamp().toString() : "",
                        "aqi", data.getPollutants() != null && data.getPollutants().getAqi() != null ? data.getPollutants().getAqi() : 0,
                        "source", "sensor_data"
                ))
                .toList();
    }

    private Map<String, Object> snapshotSummary(CityMetricsSnapshot snapshot) {
        return Map.of(
                "timestamp", snapshot.getTimestamp() != null ? snapshot.getTimestamp().toString() : "",
                "aqi", snapshot.getCurrentAqi(),
                "forecastPeakAqi", snapshot.getForecastPeakAqi(),
                "source", "city_metrics_snapshot"
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }
}
