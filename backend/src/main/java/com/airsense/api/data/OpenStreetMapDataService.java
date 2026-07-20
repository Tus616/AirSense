package com.airsense.api.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenStreetMapDataService {
    private static final int MAX_QUERY_RADIUS_METERS = 3500;
    private static final int MAX_ROAD_FEATURES = 500;
    private static final int MAX_BUILDING_FEATURES = 500;
    private static final int MAX_OTHER_FEATURES = 250;

    private final RealWorldDataProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public Optional<Map<String, Object>> fetch(double latitude, double longitude) {
        if (!properties.getOpenStreetMap().isEnabled() || latitude == 0.0 || longitude == 0.0) {
            return Optional.empty();
        }
        String key = String.format("%.4f:%.4f:%d", latitude, longitude, queryRadiusMeters());
        if (cache.containsKey(key)) {
            log.info("OSM Using Cached lat={} lon={}", latitude, longitude);
            return Optional.of(cache.get(key));
        }
        try {
            String query = overpassQuery(latitude, longitude, queryRadiusMeters());
            String response = postOverpass(query);
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Empty Overpass response");
            }
            Map<String, Object> raw = objectMapper.readValue(response, new TypeReference<>() {});
            Map<String, Object> converted = convert(raw);
            cache.put(key, converted);
            log.info("OSM Success lat={} lon={} roads={} schools={} hospitals={} parks={} water={} admin={}",
                    latitude, longitude,
                    featureCount(converted, "roads"), featureCount(converted, "schools"), featureCount(converted, "hospitals"),
                    featureCount(converted, "parks"), featureCount(converted, "water"), featureCount(converted, "adminBoundaries"));
            return Optional.of(converted);
        } catch (Exception e) {
            log.warn("OSM Failed lat={} lon={} reason={}", latitude, longitude, e.getMessage());
            cache.put(key, Map.of());
            return Optional.empty();
        }
    }

    private String postOverpass(String query) throws Exception {
        int timeoutSeconds = properties.getOpenStreetMap().getTimeoutSeconds();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getOpenStreetMap().getOverpassUrl()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Overpass status=" + response.statusCode());
        }
        return response.body();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> features(Map<String, Object> osm, String layerKey) {
        Object layer = osm != null ? osm.get(layerKey) : null;
        if (layer instanceof Map<?, ?> map) {
            Object features = map.get("features");
            if (features instanceof List<?> list) {
                return (List<Map<String, Object>>) (List<?>) list;
            }
        }
        return List.of();
    }

    public Map<String, Object> featureCollection(List<Map<String, Object>> features) {
        return Map.of("type", "FeatureCollection", "features", features != null ? features : List.of());
    }

    private String overpassQuery(double latitude, double longitude, int radiusMeters) {
        return "[out:json][timeout:" + properties.getOpenStreetMap().getTimeoutSeconds() + "];("
                + "way(around:" + radiusMeters + "," + latitude + "," + longitude + ")[highway];"
                + "way(around:" + radiusMeters + "," + latitude + "," + longitude + ")[building];"
                + "node(around:" + radiusMeters + "," + latitude + "," + longitude + ")[amenity=school];"
                + "way(around:" + radiusMeters + "," + latitude + "," + longitude + ")[amenity=school];"
                + "node(around:" + radiusMeters + "," + latitude + "," + longitude + ")[amenity=hospital];"
                + "way(around:" + radiusMeters + "," + latitude + "," + longitude + ")[amenity=hospital];"
                + "way(around:" + radiusMeters + "," + latitude + "," + longitude + ")[leisure=park];"
                + "way(around:" + radiusMeters + "," + latitude + "," + longitude + ")[natural=water];"
                + "relation(around:" + radiusMeters + "," + latitude + "," + longitude + ")[boundary=administrative];"
                + ");out body geom;";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convert(Map<String, Object> raw) {
        List<Map<String, Object>> elements = raw.get("elements") instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        buckets.put("roads", new ArrayList<>());
        buckets.put("buildings", new ArrayList<>());
        buckets.put("schools", new ArrayList<>());
        buckets.put("hospitals", new ArrayList<>());
        buckets.put("parks", new ArrayList<>());
        buckets.put("water", new ArrayList<>());
        buckets.put("adminBoundaries", new ArrayList<>());

        for (Map<String, Object> element : elements) {
            Map<String, Object> tags = element.get("tags") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            String bucket = bucket(tags);
            if (bucket == null) continue;
            List<Map<String, Object>> features = buckets.get(bucket);
            if (features.size() >= maxFeatures(bucket)) continue;
            geometry(element).ifPresent(geometry -> features.add(feature(String.valueOf(element.get("id")), geometry, tags)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        buckets.forEach((bucket, features) -> result.put(bucket, featureCollection(features)));
        result.put("metadata", Map.of(
                "rawElementCount", elements.size(),
                "queryRadiusMeters", queryRadiusMeters(),
                "featureCaps", Map.of(
                        "roads", MAX_ROAD_FEATURES,
                        "buildings", MAX_BUILDING_FEATURES,
                        "other", MAX_OTHER_FEATURES
                )
        ));
        return result;
    }

    private int queryRadiusMeters() {
        return Math.min(properties.getOpenStreetMap().getRadiusMeters(), MAX_QUERY_RADIUS_METERS);
    }

    private int maxFeatures(String bucket) {
        return switch (bucket) {
            case "roads" -> MAX_ROAD_FEATURES;
            case "buildings" -> MAX_BUILDING_FEATURES;
            default -> MAX_OTHER_FEATURES;
        };
    }

    private String bucket(Map<String, Object> tags) {
        if (tags.containsKey("highway")) return "roads";
        if (tags.containsKey("building")) return "buildings";
        if ("school".equals(tags.get("amenity"))) return "schools";
        if ("hospital".equals(tags.get("amenity"))) return "hospitals";
        if ("park".equals(tags.get("leisure"))) return "parks";
        if ("water".equals(tags.get("natural"))) return "water";
        if ("administrative".equals(tags.get("boundary"))) return "adminBoundaries";
        return null;
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> geometry(Map<String, Object> element) {
        if (element.get("lat") instanceof Number lat && element.get("lon") instanceof Number lon) {
            return Optional.of(Map.of("type", "Point", "coordinates", List.of(lon.doubleValue(), lat.doubleValue())));
        }
        Object rawGeometry = element.get("geometry");
        if (!(rawGeometry instanceof List<?> list) || list.isEmpty()) return Optional.empty();
        List<List<Double>> coordinates = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> point && point.get("lat") instanceof Number lat && point.get("lon") instanceof Number lon) {
                coordinates.add(List.of(lon.doubleValue(), lat.doubleValue()));
            }
        }
        if (coordinates.size() < 2) return Optional.empty();
        if (coordinates.size() > 3 && coordinates.get(0).equals(coordinates.get(coordinates.size() - 1))) {
            return Optional.of(Map.of("type", "Polygon", "coordinates", List.of(coordinates)));
        }
        return Optional.of(Map.of("type", "LineString", "coordinates", coordinates));
    }

    private Map<String, Object> feature(String id, Map<String, Object> geometry, Map<String, Object> tags) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("featureId", "osm-" + id);
        properties.put("source", "openstreetmap");
        properties.put("name", String.valueOf(tags.getOrDefault("name", "")));
        properties.put("osmTags", tags);
        return Map.of("type", "Feature", "id", "osm-" + id, "geometry", geometry, "properties", properties);
    }

    private int featureCount(Map<String, Object> data, String layerKey) {
        return features(data, layerKey).size();
    }
}
