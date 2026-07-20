package com.airsense.api.attribution;

import com.airsense.api.data.GeoJsonImportService;
import com.airsense.api.data.OpenStreetMapDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class GeospatialSourceEvidenceService {
    private final SourceAttributionProperties properties;
    @Autowired(required = false)
    private OpenStreetMapDataService openStreetMapDataService;
    @Autowired(required = false)
    private GeoJsonImportService geoJsonImportService;
    private final Map<String, CachedEvidence> cache = new ConcurrentHashMap<>();

    public Map<String, Object> collect(String locationKey, double latitude, double longitude) {
        String cacheKey = locationKey + ":" + properties.getLocalRadiusKm();
        CachedEvidence cached = cache.get(cacheKey);
        if (cached != null && cached.isFresh(properties.getGeospatialCacheMinutes())) {
            return cached.value();
        }
        Instant observedAt = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("provider", "OPENSTREETMAP_OVERPASS");
        result.put("observedAt", observedAt.toString());
        result.put("radiusKm", properties.getLocalRadiusKm());
        result.put("dataOrigin", "UNAVAILABLE");
        result.put("warnings", new ArrayList<>(List.of("OSM_EVIDENCE_UNAVAILABLE")));
        if (openStreetMapDataService == null || latitude == 0.0 || longitude == 0.0) {
            cache.put(cacheKey, new CachedEvidence(result, observedAt));
            return result;
        }

        Optional<Map<String, Object>> osm = openStreetMapDataService.fetch(latitude, longitude);
        if (osm.isEmpty()) {
            cache.put(cacheKey, new CachedEvidence(result, observedAt));
            return result;
        }

        List<Map<String, Object>> roads = openStreetMapDataService.features(osm.get(), "roads");
        List<Map<String, Object>> buildings = openStreetMapDataService.features(osm.get(), "buildings");
        double roadLength = roads.stream().mapToDouble(this::lengthKm).sum();
        double area = Math.PI * properties.getLocalRadiusKm() * properties.getLocalRadiusKm();
        Map<String, Long> roadClasses = roadClasses(roads);
        List<Map<String, Object>> industrial = importedFeatures("industrial");
        List<Map<String, Object>> construction = importedFeatures("construction");

        result.put("available", true);
        result.put("dataOrigin", SourceScoringRule.DATA_ORIGIN);
        result.put("warnings", List.of("OSM_FEATURES_ARE_INCOMPLETE_PROXIES"));
        result.put("rawOsm", osm.get());
        result.put("roadFeatureCount", roads.size());
        result.put("buildingFeatureCount", buildings.size());
        result.put("majorRoadLengthKm", round(roadLength));
        result.put("roadDensityKmPerSquareKm", round(area > 0 ? roadLength / area : 0.0));
        result.put("nearestMajorRoadDistanceKm", nearestDistanceKm(latitude, longitude, roads));
        result.put("roadClasses", roadClasses);
        result.put("intersectionProxyCount", Math.max(0, roads.size() / 4));
        result.put("constructionSiteCount", construction.size());
        result.put("industrialFeatureCount", industrial.size());
        result.put("landfillCount", countTagged(osm.get(), "landfill", "waste"));
        result.put("powerPlantCount", countTagged(osm.get(), "power", "plant"));
        result.put("greenAreaPercent", null);
        result.put("industrialFeatures", industrial);
        result.put("constructionFeatures", construction);
        cache.put(cacheKey, new CachedEvidence(result, observedAt));
        return result;
    }

    private Map<String, Long> roadClasses(List<Map<String, Object>> roads) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> road : roads) {
            String highway = lower(String.valueOf(properties(road).getOrDefault("highway", "")));
            if (highway.isBlank() && properties(road).get("osmTags") instanceof Map<?, ?> tags) {
                Object tagValue = tags.get("highway");
                highway = lower(tagValue != null ? String.valueOf(tagValue) : "");
            }
            if (highway.isBlank()) highway = "unknown";
            counts.put(highway, counts.getOrDefault(highway, 0L) + 1);
        }
        return counts;
    }

    private long countTagged(Map<String, Object> osm, String... needles) {
        return List.of("roads", "buildings", "parks", "water").stream()
                .flatMap(layer -> openStreetMapDataService.features(osm, layer).stream())
                .filter(feature -> {
                    String text = lower(String.valueOf(properties(feature)));
                    for (String needle : needles) {
                        if (text.contains(needle)) return true;
                    }
                    return false;
                }).count();
    }

    private Map<String, Object> properties(Map<String, Object> feature) {
        Object props = feature.get("properties");
        return props instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<Map<String, Object>> importedFeatures(String layerKey) {
        if (geoJsonImportService == null) return List.of();
        return geoJsonImportService.load(layerKey)
                .map(geoJson -> {
                    Object features = geoJson.get("features");
                    if (!(features instanceof List<?> list)) return List.<Map<String, Object>>of();
                    List<Map<String, Object>> converted = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            converted.add((Map<String, Object>) map);
                        }
                    }
                    return converted;
                })
                .orElse(List.of());
    }

    private double lengthKm(Map<String, Object> feature) {
        Map<String, Object> geometry = map(feature.get("geometry"));
        if (!"LineString".equals(geometry.get("type"))) return 0.0;
        Object coordinates = geometry.get("coordinates");
        if (!(coordinates instanceof List<?> list) || list.size() < 2) return 0.0;
        double total = 0.0;
        Point previous = null;
        for (Object item : list) {
            Point current = point(item);
            if (current != null && previous != null) total += distanceKm(previous.lat(), previous.lon(), current.lat(), current.lon());
            if (current != null) previous = current;
        }
        return total;
    }

    private Object nearestDistanceKm(double latitude, double longitude, List<Map<String, Object>> roads) {
        return roads.stream()
                .map(feature -> firstPoint(map(feature.get("geometry")).get("coordinates")))
                .flatMap(Optional::stream)
                .mapToDouble(point -> distanceKm(latitude, longitude, point.lat(), point.lon()))
                .min()
                .stream()
                .map(this::round)
                .boxed()
                .findFirst()
                .orElse(null);
    }

    private Optional<Point> firstPoint(Object coordinates) {
        Point point = point(coordinates);
        if (point != null) return Optional.of(point);
        if (coordinates instanceof List<?> list) {
            for (Object item : list) {
                Optional<Point> nested = firstPoint(item);
                if (nested.isPresent()) return nested;
            }
        }
        return Optional.empty();
    }

    private Point point(Object value) {
        if (value instanceof List<?> list && list.size() >= 2 && list.get(0) instanceof Number lon && list.get(1) instanceof Number lat) {
            return new Point(lat.doubleValue(), lon.doubleValue());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthKm * Math.asin(Math.sqrt(a));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record Point(double lat, double lon) {}

    private record CachedEvidence(Map<String, Object> value, Instant cachedAt) {
        private boolean isFresh(int ttlMinutes) {
            return cachedAt.plusSeconds(Math.max(1, ttlMinutes) * 60L).isAfter(Instant.now());
        }
    }
}
