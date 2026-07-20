package com.airsense.api.attribution;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FireHotspotEvidenceService {
    private final SourceAttributionProperties properties;
    private final Map<String, CachedEvidence> cache = new ConcurrentHashMap<>();

    public FireHotspotEvidenceService(SourceAttributionProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> collect(String locationKey, double latitude, double longitude, double windDirectionDegrees, double windSpeedMps) {
        String cacheKey = locationKey + ":" + properties.getFireRadiusKm();
        CachedEvidence cached = cache.get(cacheKey);
        if (cached != null && cached.isFresh(properties.getFireCacheMinutes())) {
            return cached.value();
        }
        Instant observedAt = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("provider", "NASA_FIRMS");
        result.put("queryRadiusKm", properties.getFireRadiusKm());
        result.put("dataOrigin", "UNAVAILABLE");
        result.put("observedAt", observedAt.toString());
        result.put("detections", List.of());
        result.put("relevantDetectionCount", 0);
        result.put("aggregateFireInfluenceScore", 0.0);
        result.put("warnings", new ArrayList<>(List.of("FIRE_PROVIDER_UNAVAILABLE")));
        if (properties.getNasaFirmsApiKey() == null || properties.getNasaFirmsApiKey().isBlank()) {
            cache.put(cacheKey, new CachedEvidence(result, observedAt));
            return result;
        }
        result.put("warnings", List.of("FIRE_PROVIDER_CONFIGURED_BUT_RUNTIME_FETCH_NOT_ENABLED_IN_THIS_BUILD"));
        cache.put(cacheKey, new CachedEvidence(result, observedAt));
        return result;
    }

    public double windAlignmentScore(double fireLat, double fireLon, double cityLat, double cityLon, double windDirectionDegrees) {
        if (windDirectionDegrees <= 0) return 0.0;
        double bearingFireToCity = bearingDegrees(fireLat, fireLon, cityLat, cityLon);
        double windToward = (windDirectionDegrees + 180.0) % 360.0;
        double difference = Math.abs(windToward - bearingFireToCity);
        difference = Math.min(difference, 360.0 - difference);
        return Math.max(0.0, 1.0 - (difference / 90.0));
    }

    private double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double lambda = Math.toRadians(lon2 - lon1);
        double y = Math.sin(lambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(lambda);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    private record CachedEvidence(Map<String, Object> value, Instant cachedAt) {
        private boolean isFresh(int ttlMinutes) {
            return cachedAt.plusSeconds(Math.max(1, ttlMinutes) * 60L).isAfter(Instant.now());
        }
    }
}
