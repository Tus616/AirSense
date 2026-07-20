package com.airsense.api.forecast;

import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.entities.SensorData;
import com.airsense.api.repositories.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastSnapshotService {
    private final SensorDataRepository sensorDataRepository;

    public void record(DecisionIntelligenceResult decision) {
        if (decision == null || decision.getEnvironmentalSignals() == null
                || decision.getCurrentAQI() == null || decision.getCurrentAQI() <= 0) {
            return;
        }

        Map<String, Object> signals = decision.getEnvironmentalSignals();
        Map<String, Object> pollutants = asMap(signals.get("pollutants"));
        Instant observedAt = instant(signals.get("observedAt"));
        Instant timestamp = observedAt != null ? observedAt : decision.getGeneratedAt() != null ? decision.getGeneratedAt() : Instant.now();
        String stationName = string(signals.get("stationName"));
        String cityId = valueOrDefault(decision.getCityId(), decision.getCity());
        String cityName = valueOrDefault(decision.getCity(), decision.getCityId());
        String wardId = valueOrDefault(string(signals.get("wardId")), valueOrDefault(decision.getCityId(), "UNKNOWN_WARD"));
        Double latitude = number(signals.get("stationLatitude"));
        Double longitude = number(signals.get("stationLongitude"));
        String sensorId = buildSensorId(cityId, stationName, latitude, longitude, valueOrDefault(decision.getCityId(), "UNKNOWN_SENSOR"));

        SensorData latest = sensorDataRepository.findFirstBySensorIdOrderByTimestampDesc(sensorId).orElse(null);
        if (latest != null && latest.getTimestamp() != null && latest.getTimestamp().equals(timestamp)) {
            log.info("ForecastSnapshot skip duplicate sensorId={} timestamp={}", sensorId, timestamp);
            return;
        }

        SensorData snapshot = SensorData.builder()
                .timestamp(timestamp)
                .sensorId(sensorId)
                .stationName(valueOrDefault(stationName, cityName))
                .cityId(cityId)
                .cityName(cityName)
                .wardId(wardId)
                .coordinates((latitude != null && longitude != null)
                        ? SensorData.GeoJsonPoint.builder().coordinates(List.of(longitude, latitude)).build()
                        : null)
                .pollutants(SensorData.Pollutants.builder()
                        .aqi(decision.getCurrentAQI())
                        .pm25(number(pollutants.get("pm25")))
                        .pm10(number(pollutants.get("pm10")))
                        .co(number(pollutants.get("co")))
                        .no2(number(pollutants.get("no2")))
                        .so2(number(pollutants.get("so2")))
                        .o3(number(pollutants.get("o3")))
                        .build())
                .weather(SensorData.Weather.builder()
                        .windSpeed(number(asMap(signals.get("weather")).get("windSpeed")))
                        .windDirection(number(asMap(signals.get("weather")).get("windDirection")))
                        .temperature(number(asMap(signals.get("weather")).get("temperature")))
                        .humidity(number(asMap(signals.get("weather")).get("humidity")))
                        .pressure(number(asMap(signals.get("weather")).get("pressure")))
                        .rainfall(number(asMap(signals.get("weather")).get("rainfall")))
                        .build())
                .traffic(SensorData.Traffic.builder()
                        .congestionIndex(number(asMap(signals.get("traffic")).get("averageCongestionIndex")))
                        .averageSpeed(number(asMap(signals.get("traffic")).get("averageSpeed")))
                        .flow(number(asMap(signals.get("traffic")).get("flow")))
                        .build())
                .landUse(SensorData.LandUse.builder()
                        .primaryType(string(asMap(signals.get("landUse")).get("primaryType")))
                        .tags(List.of())
                        .build())
                .qualityFlags(SensorData.QualityFlags.builder()
                        .missingFields(List.of())
                        .imputedFields(List.of())
                        .anomalySmoothed(Boolean.FALSE)
                        .build())
                .sourceMetadata(SensorData.SourceMetadata.builder()
                        .sourceNames(List.of(
                                valueOrDefault(string(signals.get("provider")), "CPCB_CAAQMS"),
                                "OpenWeather",
                                "Fusion"
                        ))
                        .fetchedAt(decision.getGeneratedAt() != null ? decision.getGeneratedAt() : Instant.now())
                        .rawObjectRefs(Map.of(
                                "observedAt", valueOrDefault(signals.get("observedAt") != null ? signals.get("observedAt").toString() : null, timestamp.toString()),
                                "aqiStandard", valueOrDefault(string(signals.get("aqiStandard")), "CPCB_INDIAN_NAQI_0_500"),
                                "freshnessStatus", valueOrDefault(string(signals.get("freshnessStatus")), "LIVE")
                        ))
                        .build())
                .build();

        try {
            sensorDataRepository.save(snapshot);
            log.info("ForecastSnapshot saved sensorId={} cityId={} observedAt={} aqi={}",
                    sensorId, cityId, timestamp, decision.getCurrentAQI());
        } catch (Exception e) {
            log.warn("ForecastSnapshot save failed sensorId={} cityId={} reason={}", sensorId, cityId, e.getMessage());
        }
    }

    private String buildSensorId(String cityId, String stationName, Double latitude, Double longitude, String fallback) {
        if (stationName != null && !stationName.isBlank() && latitude != null && longitude != null) {
            return cityId + "|" + stationName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_")
                    + "|" + round(latitude) + "," + round(longitude);
        }
        if (stationName != null && !stationName.isBlank()) {
            return cityId + "|" + stationName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        }
        return fallback;
    }

    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String string(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String round(Double value) {
        if (value == null) {
            return "0.000";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
