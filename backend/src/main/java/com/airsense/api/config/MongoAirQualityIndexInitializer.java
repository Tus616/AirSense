package com.airsense.api.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MongoAirQualityIndexInitializer implements ApplicationRunner {
    private final MongoTemplate mongoTemplate;
    private final AirQualityOperationsProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isIndexInitializationEnabled()) {
            log.info("Air-quality Mongo index initialization disabled.");
            return;
        }
        requiredIndexes().forEach(this::ensure);
    }

    public List<Map<String, Object>> status() {
        return requiredIndexes().stream().map(this::status).toList();
    }

    public List<IndexDefinitionSpec> requiredIndexes() {
        return List.of(
                new IndexDefinitionSpec("aqi_historical_snapshots", "uq_aqi_snapshot_identity",
                        ordered("locationKey", 1, "provider", 1, "aqiStandard", 1, "providerObservedAt", 1), true),
                new IndexDefinitionSpec("aqi_historical_snapshots", "idx_aqi_history_lookup",
                        ordered("locationKey", 1, "aqiStandard", 1, "providerObservedAt", -1), false),
                new IndexDefinitionSpec("aqi_historical_snapshots", "idx_aqi_history_ingested",
                        ordered("locationKey", 1, "ingestedAt", -1), false),
                new IndexDefinitionSpec("aqi_historical_snapshots", "idx_aqi_station_history_lookup",
                        ordered("stationKey", 1, "aqiStandard", 1, "providerObservedAt", -1), false),
                new IndexDefinitionSpec("tracked_air_quality_locations", "uq_tracked_location_key",
                        ordered("locationKey", 1), true),
                new IndexDefinitionSpec("tracked_air_quality_locations", "idx_tracked_location_active",
                        ordered("trackingEnabled", 1, "lastSearchedAt", -1), false),
                new IndexDefinitionSpec("aqi_forecast_runs", "idx_forecast_evaluation_lookup",
                        ordered("locationKey", 1, "forecastStandard", 1, "targetTime", 1, "evaluated", 1), false),
                new IndexDefinitionSpec("aqi_forecast_runs", "idx_forecast_metrics_lookup",
                        ordered("locationKey", 1, "forecastStandard", 1, "engine", 1, "horizonHours", 1, "generatedAt", -1), false),
                new IndexDefinitionSpec("aqi_forecast_runs", "idx_forecast_identity",
                        ordered("locationKey", 1, "forecastStandard", 1, "engine", 1, "modelVersion", 1,
                                "generatedAt", 1, "targetTime", 1, "horizonHours", 1), false)
        );
    }

    private void ensure(IndexDefinitionSpec spec) {
        Map<String, Object> current = status(spec);
        if (Boolean.TRUE.equals(current.get("exists")) && !Boolean.TRUE.equals(current.get("matchingKeys"))) {
            log.error("Air-quality Mongo index {} exists on {} but has incompatible keys. expected={} actual={}",
                    spec.name(), spec.collection(), spec.keys(), current.get("actualKeys"));
            return;
        }
        try {
            mongoTemplate.indexOps(spec.collection()).ensureIndex(toIndex(spec));
            log.info("Air-quality Mongo index verified collection={} name={}", spec.collection(), spec.name());
        } catch (Exception e) {
            log.error("Air-quality Mongo index initialization failed collection={} name={} reason={}",
                    spec.collection(), spec.name(), e.getMessage());
            throw e;
        }
    }

    private Map<String, Object> status(IndexDefinitionSpec spec) {
        Document existing = existingIndex(spec.collection(), spec.name());
        boolean exists = existing != null;
        Document actualKeys = exists ? existing.get("key", Document.class) : null;
        boolean matchingKeys = exists && spec.keys().equals(toIntegerMap(actualKeys));
        boolean unique = exists && Boolean.TRUE.equals(existing.getBoolean("unique", false));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("collection", spec.collection());
        response.put("requiredIndexName", spec.name());
        response.put("exists", exists);
        response.put("unique", unique);
        response.put("expectedUnique", spec.unique());
        response.put("matchingKeys", matchingKeys);
        response.put("expectedKeys", spec.keys());
        response.put("actualKeys", actualKeys != null ? actualKeys : Map.of());
        response.put("status", !exists ? "MISSING" : matchingKeys && unique == spec.unique() ? "OK" : "MISMATCH");
        return response;
    }

    private Document existingIndex(String collection, String name) {
        for (Document index : mongoTemplate.getCollection(collection).listIndexes()) {
            if (name.equals(index.getString("name"))) {
                return index;
            }
        }
        return null;
    }

    private Index toIndex(IndexDefinitionSpec spec) {
        Index index = new Index().named(spec.name());
        spec.keys().forEach((field, direction) ->
                index.on(field, direction >= 0 ? Sort.Direction.ASC : Sort.Direction.DESC));
        return spec.unique() ? index.unique() : index;
    }

    private static LinkedHashMap<String, Integer> ordered(Object... keysAndDirections) {
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (int i = 0; i < keysAndDirections.length; i += 2) {
            ordered.put(String.valueOf(keysAndDirections[i]), ((Number) keysAndDirections[i + 1]).intValue());
        }
        return ordered;
    }

    private static Map<String, Integer> toIntegerMap(Document document) {
        if (document == null) return Map.of();
        Map<String, Integer> map = new LinkedHashMap<>();
        document.forEach((key, value) -> {
            if (value instanceof Number number) map.put(key, number.intValue());
        });
        return map;
    }

    public record IndexDefinitionSpec(String collection, String name, Map<String, Integer> keys, boolean unique) {
    }
}
