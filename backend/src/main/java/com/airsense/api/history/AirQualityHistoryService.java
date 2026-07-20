package com.airsense.api.history;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.forecast.ForecastEngineProperties;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AirQualityHistoryService {
    private final AqiHistoricalSnapshotRepository snapshotRepository;
    private final ForecastEngineProperties forecastProperties;
    private final CanonicalLocationIdentityService locationIdentityService;
    private final HistoricalAqiProperties historicalProperties;

    public Map<String, Object> timeline(String city, String state, String country, Double lat, Double lon, int hours) {
        CanonicalLocationIdentity identity = locationIdentityService.identity(city, state, country, lat, lon);
        List<String> queryKeys = locationIdentityService.queryKeys(identity);
        Instant end = queryEnd();
        Instant start = end.minus(Duration.ofHours(Math.max(1, hours)));
        List<AqiHistoricalSnapshot> observations = snapshotRepository
                .findByLocationKeyInAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(queryKeys, start, end);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("location", location(identity, city, state, country, lat, lon));
        response.put("query", query(identity, queryKeys, start, end, null, null, observations.size()));
        response.put("requestedHours", hours);
        response.put("observationCount", observations.size());
        response.put("aqiStandardsPresent", observations.stream().map(AqiHistoricalSnapshot::getAqiStandard).filter(v -> v != null && !v.isBlank()).distinct().toList());
        response.put("observations", observations);
        response.put("warnings", warnings(observations));
        return response;
    }

    public Map<String, Object> quality(String city, String state, String country, Double lat, Double lon, int days) {
        CanonicalLocationIdentity identity = locationIdentityService.identity(city, state, country, lat, lon);
        List<String> queryKeys = locationIdentityService.queryKeys(identity);
        Instant end = queryEnd();
        Instant start = end.minus(Duration.ofDays(Math.max(1, days)));
        List<AqiHistoricalSnapshot> observations = snapshotRepository
                .findByLocationKeyInAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(queryKeys, start, end);
        Map<String, Long> quality = observations.stream().collect(Collectors.groupingBy(
                item -> valueOrDefault(item.getDataQualityStatus(), "UNKNOWN"), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> providerDistribution = observations.stream().collect(Collectors.groupingBy(
                item -> valueOrDefault(item.getProvider(), "UNKNOWN"), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> standardDistribution = observations.stream().collect(Collectors.groupingBy(
                item -> valueOrDefault(item.getAqiStandard(), "UNKNOWN"), LinkedHashMap::new, Collectors.counting()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("location", location(identity, city, state, country, lat, lon));
        response.put("query", query(identity, queryKeys, start, end, null, null, observations.size()));
        response.put("filter", Map.of("aqiStandard", "ALL", "provider", "ALL"));
        response.put("sampleWindowDays", days);
        response.put("totalObservations", observations.size());
        response.put("uniqueObservationCount", uniqueObservationCount(observations));
        response.put("duplicateCount", Math.max(0, observations.size() - uniqueObservationCount(observations)));
        response.put("validObservations", quality.getOrDefault("VALID", 0L));
        response.put("partialObservations", quality.getOrDefault("PARTIAL", 0L));
        response.put("staleObservations", quality.getOrDefault("STALE", 0L));
        response.put("missingPollutantCounts", Map.of(
                "pm25", observations.stream().filter(item -> item.getPm25() == null).count(),
                "pm10", observations.stream().filter(item -> item.getPm10() == null).count()
        ));
        response.put("providerDistribution", providerDistribution);
        response.put("aqiStandardDistribution", standardDistribution);
        response.put("standardDistribution", standardDistribution);
        response.put("timeCoverageHours", coverageHours(observations));
        response.put("largestGapHours", largestGapHours(observations));
        response.put("oldestObservationTime", observations.stream().map(AqiHistoricalSnapshot::getProviderObservedAt).filter(v -> v != null).min(Comparator.naturalOrder()).map(Instant::toString).orElse(null));
        response.put("latestObservationTime", observations.stream().map(AqiHistoricalSnapshot::getProviderObservedAt).max(Comparator.naturalOrder()).map(Instant::toString).orElse(null));
        response.put("latestObservation", observations.isEmpty() ? null : observations.get(observations.size() - 1));
        response.put("forecastReadiness", Map.of(
                "persistence", observations.stream().anyMatch(item -> item.getCurrentAqi() != null),
                "trendWeather", observations.stream().filter(item -> item.getCurrentAqi() != null).count() >= forecastProperties.getMinValidObservations(),
                "horizon24h", sufficiency(observations, 24),
                "horizon48h", sufficiency(observations, 48),
                "horizon72h", sufficiency(observations, 72)
        ));
        return response;
    }

    private List<String> warnings(List<AqiHistoricalSnapshot> observations) {
        if (observations.isEmpty()) return List.of("NO_HISTORY_FOR_LOCATION");
        if (observations.stream().map(AqiHistoricalSnapshot::getAqiStandard).filter(v -> v != null && !v.isBlank()).distinct().count() > 1) {
            return List.of("MULTIPLE_AQI_STANDARDS_PRESENT");
        }
        return List.of();
    }

    private double coverageHours(List<AqiHistoricalSnapshot> observations) {
        if (observations.size() < 2) return 0.0;
        Instant first = observations.get(0).getProviderObservedAt();
        Instant last = observations.get(observations.size() - 1).getProviderObservedAt();
        return first != null && last != null ? round(Duration.between(first, last).toMinutes() / 60.0) : 0.0;
    }

    private double largestGapHours(List<AqiHistoricalSnapshot> observations) {
        double max = 0.0;
        for (int i = 1; i < observations.size(); i++) {
            Instant prev = observations.get(i - 1).getProviderObservedAt();
            Instant current = observations.get(i).getProviderObservedAt();
            if (prev != null && current != null) {
                max = Math.max(max, Duration.between(prev, current).toMinutes() / 60.0);
            }
        }
        return round(max);
    }

    private Map<String, Object> sufficiency(List<AqiHistoricalSnapshot> observations, int horizon) {
        long valid = observations.stream().filter(item -> item.getCurrentAqi() != null && item.getProviderObservedAt() != null).count();
        double coverage = coverageHours(observations);
        int requiredCount = forecastProperties.requiredObservations(horizon);
        int requiredCoverage = forecastProperties.requiredCoverageHours(horizon);
        return Map.of(
                "sufficient", valid >= requiredCount && coverage >= requiredCoverage,
                "validObservationCount", valid,
                "requiredObservationCount", requiredCount,
                "coverageHours", coverage,
                "requiredCoverageHours", requiredCoverage
        );
    }

    private long uniqueObservationCount(List<AqiHistoricalSnapshot> observations) {
        return observations.stream()
                .map(item -> valueOrDefault(item.getLocationKey(), "") + "|"
                        + valueOrDefault(item.getProvider(), "") + "|"
                        + valueOrDefault(item.getAqiStandard(), "") + "|"
                        + (item.getProviderObservedAt() != null ? item.getProviderObservedAt() : ""))
                .distinct()
                .count();
    }

    private Map<String, Object> location(CanonicalLocationIdentity identity, String city, String state, String country, Double lat, Double lon) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("locationKey", identity.locationKey());
        location.put("locationKeyVersion", identity.keyVersion());
        location.put("countryCode", identity.countryCode());
        location.put("roundedLatitude", identity.roundedLatitude());
        location.put("roundedLongitude", identity.roundedLongitude());
        location.put("legacyLocationKeys", identity.legacyLocationKeys());
        location.put("city", city);
        location.put("state", state);
        location.put("country", country);
        location.put("latitude", lat);
        location.put("longitude", lon);
        return location;
    }

    private Map<String, Object> query(CanonicalLocationIdentity identity, List<String> queryKeys, Instant start, Instant end,
                                      String aqiStandard, String provider, int matchedCount) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("canonicalLocationKey", identity.locationKey());
        query.put("locationKeyVersion", identity.keyVersion());
        query.put("legacyKeysAttempted", queryKeys.stream().filter(key -> !key.equals(identity.locationKey())).toList());
        query.put("startTimestamp", start);
        query.put("endTimestamp", end);
        query.put("aqiStandardFilter", aqiStandard);
        query.put("providerFilter", provider);
        query.put("matchedRecordCount", matchedCount);
        return query;
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Instant queryEnd() {
        return Instant.now().plus(Duration.ofMinutes(Math.max(0, historicalProperties.getProviderTimestampFutureToleranceMinutes())));
    }
}
