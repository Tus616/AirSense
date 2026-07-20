package com.airsense.api.controllers;

import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.PollutionAttributionService;
import com.airsense.api.config.MongoAirQualityIndexInitializer;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import com.airsense.api.forecast.ForecastDriftMonitorService;
import com.airsense.api.forecast.ForecastOrchestrator;
import com.airsense.api.forecast.ForecastRequest;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.history.CanonicalLocationIdentity;
import com.airsense.api.history.CanonicalLocationIdentityService;
import com.airsense.api.history.DataOrigin;
import com.airsense.api.history.HistoricalAqiProperties;
import com.airsense.api.repositories.AqiForecastRunRepository;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import com.airsense.api.repositories.TrackedAirQualityLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/debug")
public class AqiDebugController {
    private final DataFusionService dataFusionService;
    private final AqiHistoricalSnapshotRepository historicalSnapshotRepository;
    private final AqiForecastRunRepository forecastRunRepository;
    private final TrackedAirQualityLocationRepository trackedLocationRepository;
    private final MongoAirQualityIndexInitializer mongoAirQualityIndexInitializer;
    private final CanonicalLocationIdentityService locationIdentityService;
    private final HistoricalAqiProperties historicalAqiProperties;
    private final PollutionAttributionService pollutionAttributionService;
    @Qualifier("hyperlocalForecastOrchestrator")
    private final ForecastOrchestrator forecastOrchestrator;
    private final ForecastDriftMonitorService driftMonitorService;

    @GetMapping("/aqi")
    public ResponseEntity<Map<String, Object>> debugAqi(
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country) {
        FusionRequest request = FusionRequest.builder()
                .cityId(city != null && !city.isBlank() ? city : "UNKNOWN_PLACE")
                .cityName(city)
                .state(state)
                .country(country)
                .latitude(lat)
                .longitude(lon)
                .build();
        CityEnvironmentalContext context = dataFusionService.buildContext(request);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("snapshotId", context.getMetadata().get("snapshotId"));
        response.put("locationHash", context.getMetadata().get("locationHash"));
        response.put("providerStatus", context.getProviderStatus());
        response.put("providerTimestamps", context.getMetadata().get("providerTimestamps"));
        response.put("aqi", context.getAqi());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/source-attribution")
    public ResponseEntity<Map<String, Object>> debugSourceAttribution(
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country,
            @RequestParam(required = false, defaultValue = "false") boolean refreshEvidence) {
        return ResponseEntity.ok(pollutionAttributionService.debug(AttributionRequest.builder()
                .cityId(city != null && !city.isBlank() ? city : "UNKNOWN_PLACE")
                .cityName(city)
                .state(state)
                .country(country)
                .latitude(lat)
                .longitude(lon)
                .refreshEvidence(refreshEvidence)
                .build()));
    }

    @GetMapping("/forecast")
    public ResponseEntity<Map<String, Object>> debugForecast(
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country) {
        String safeCity = city != null && !city.isBlank() ? city : "UNKNOWN_PLACE";
        ForecastRequest forecastRequest = ForecastRequest.builder()
                .cityId(safeCity)
                .cityName(safeCity)
                .state(state)
                .country(country)
                .latitude(lat)
                .longitude(lon)
                .build();
        FusionRequest fusionRequest = FusionRequest.builder()
                .cityId(safeCity)
                .cityName(safeCity)
                .state(state)
                .country(country)
                .latitude(lat)
                .longitude(lon)
                .build();
        CityEnvironmentalContext context = dataFusionService.buildContext(fusionRequest);
        ForecastResult forecast = forecastOrchestrator.forecast(context, forecastRequest);
        Map<String, Object> selected = asMap(safeMap(context.getAqi()).get("selected"));
        String standard = valueOrDefault(string(selected.get("standard")), forecast.getForecastStandard());
        CanonicalLocationIdentity identity = locationIdentityService.identity(safeCity, state, country, lat, lon);
        List<String> queryKeys = locationIdentityService.queryKeys(identity);
        Instant end = Instant.now().plus(Duration.ofMinutes(Math.max(0, historicalAqiProperties.getProviderTimestampFutureToleranceMinutes())));
        Instant start = end.minus(Duration.ofHours(Math.max(1, forecast.getDataWindowHours())));
        var history = historicalSnapshotRepository
                .findByLocationKeyInAndAqiStandardAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(queryKeys, standard, start, end);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("snapshotId", forecast.getSnapshotId());
        response.put("locationHash", forecast.getLocationHash());
        response.put("canonicalLocation", canonicalMap(identity));
        response.put("legacyKeysAttempted", identity.legacyLocationKeys());
        response.put("queryTimeRange", Map.of("start", start, "end", end));
        response.put("standardFilter", standard);
        response.put("matchedRecordCount", history.size());
        response.put("currentSelectedAqi", selected);
        response.put("forecastStandard", forecast.getForecastStandard());
        response.put("historicalObservationsUsed", history);
        response.put("excludedObservations", List.of("Only observations matching " + standard + " and one of " + queryKeys + " are used."));
        response.put("exclusionReasons", List.of("DIFFERENT_LOCATION_KEY", "DIFFERENT_AQI_STANDARD", "OUTSIDE_HISTORY_WINDOW", "INVALID_AQI_OR_TIMESTAMP"));
        response.put("deduplicatedObservationCount", history.stream().map(item -> item.getProviderObservedAt()).distinct().count());
        response.put("timeCoverageHours", coverageHours(history.stream().map(item -> item.getProviderObservedAt()).sorted().toList()));
        response.put("largestGap", largestGapHours(history.stream().map(item -> item.getProviderObservedAt()).sorted().toList()));
        response.put("dataGaps", dataGaps(history.stream().map(item -> item.getProviderObservedAt()).sorted().toList()));
        response.put("perHorizonSufficiency", forecast.getForecast().values().stream().map(point -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("horizonHours", point.getHorizonHours());
            item.put("selectedMode", valueOrDefault(point.getMode(), "UNAVAILABLE"));
            item.put("sufficientHistory", point.isSufficientHistory());
            item.put("validObservationCount", point.getValidObservationCount());
            item.put("coverageHours", point.getCoverageHours());
            item.put("requiredObservationCount", point.getRequiredObservationCount());
            item.put("requiredCoverageHours", point.getRequiredCoverageHours());
            item.put("insufficiencyReasons", point.getInsufficiencyReasons());
            item.put("confidenceReductionReasons", point.getConfidenceReductionReasons());
            item.put("dataOrigin", valueOrDefault(point.getDataOrigin(), "UNAVAILABLE"));
            return item;
        }).toList());
        response.put("rollingStatistics", rollingStatistics(history.stream().map(item -> item.getCurrentAqi()).filter(value -> value != null && value > 0).toList()));
        response.put("slope", response.get("rollingStatistics"));
        response.put("futureWeatherFeatures", safeMap(context.getWeather()).get("hourlyForecast"));
        response.put("weatherAdjustments", forecast.getForecast().values().stream().collect(java.util.stream.Collectors.toMap(
                point -> point.getHorizonHours() + "h",
                point -> point.getDrivers() != null ? point.getDrivers() : List.of(),
                (left, right) -> left,
                LinkedHashMap::new
        )));
        response.put("engineSelected", forecast.getEngine());
        response.put("persistenceBaseline", forecast.getBaseline());
        response.put("finalForecasts", forecast.getForecast());
        response.put("dataOrigin", DataOrigin.DERIVED_FROM_REAL_DATA.name());
        response.put("confidenceCalculationFactors", Map.of(
                "observationCount", forecast.getObservationCount(),
                "dataQuality", valueOrDefault(forecast.getDataQuality(), "UNKNOWN"),
                "provider", valueOrDefault(forecast.getCurrentProvider(), "UNKNOWN"),
                "warnings", forecast.getWarnings()
        ));
        response.put("intervalCalculationInputs", Map.of(
                "approach", "recent AQI standard deviation plus horizon and confidence expansion",
                "horizons", List.of(24, 48, 72)
        ));
        response.put("warnings", forecast.getWarnings());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/database/indexes")
    public ResponseEntity<Map<String, Object>> databaseIndexes() {
        return ResponseEntity.ok(Map.of("indexes", mongoAirQualityIndexInitializer.status()));
    }

    @GetMapping("/location-identity")
    public ResponseEntity<Map<String, Object>> locationIdentity(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country) {
        CanonicalLocationIdentity identity = locationIdentityService.identity(city, state, country, latitude, longitude);
        List<String> queryKeys = locationIdentityService.queryKeys(identity);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("canonical", canonicalMap(identity));
        response.put("legacyCandidates", identity.legacyLocationKeys());
        response.put("matchedSnapshotCount", historicalSnapshotRepository.countByLocationKeyIn(queryKeys));
        response.put("matchedTrackedLocationCount", trackedLocationRepository.countByLocationKeyIn(queryKeys));
        response.put("matchedForecastRunCount", forecastRunRepository.findByLocationKeyInAndGeneratedAtBetween(
                queryKeys, Instant.EPOCH, Instant.now().plus(Duration.ofDays(1))).size());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> rollingStatistics(List<Integer> values) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", values.size());
        stats.put("latest", values.isEmpty() ? null : values.get(values.size() - 1));
        stats.put("mean", values.isEmpty() ? null : round(values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0)));
        stats.put("median", values.isEmpty() ? null : median(values));
        stats.put("slopePerHour", values.size() < 2 ? 0.0 : "computed in forecast engine from ordered timestamps");
        return stats;
    }

    private Map<String, Object> canonicalMap(CanonicalLocationIdentity identity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("locationKey", identity.locationKey());
        map.put("keyVersion", identity.keyVersion());
        map.put("countryCode", identity.countryCode());
        map.put("roundedLatitude", identity.roundedLatitude());
        map.put("roundedLongitude", identity.roundedLongitude());
        map.put("normalizedCity", identity.normalizedCity());
        map.put("normalizedState", identity.normalizedState());
        map.put("normalizedCountry", identity.normalizedCountry());
        return map;
    }

    private List<Map<String, Object>> dataGaps(List<Instant> timestamps) {
        return timestamps.stream()
                .sorted(Comparator.naturalOrder())
                .reduce(new java.util.ArrayList<Map<String, Object>>(), (gaps, current) -> {
                    if (!timestamps.isEmpty()) {
                        int index = timestamps.indexOf(current);
                        if (index > 0) {
                            Instant previous = timestamps.get(index - 1);
                            long hours = Duration.between(previous, current).toHours();
                            if (hours > 6) {
                                gaps.add(Map.of("from", previous, "to", current, "gapHours", hours));
                            }
                        }
                    }
                    return gaps;
                }, (left, right) -> left);
    }

    private double coverageHours(List<Instant> timestamps) {
        if (timestamps.size() < 2) return 0.0;
        return round(Duration.between(timestamps.get(0), timestamps.get(timestamps.size() - 1)).toMinutes() / 60.0);
    }

    private double largestGapHours(List<Instant> timestamps) {
        double largest = 0.0;
        for (int i = 1; i < timestamps.size(); i++) {
            largest = Math.max(largest, Duration.between(timestamps.get(i - 1), timestamps.get(i)).toMinutes() / 60.0);
        }
        return round(largest);
    }

    private Object median(List<Integer> values) {
        if (values.isEmpty()) return null;
        List<Integer> ordered = values.stream().sorted().toList();
        int middle = ordered.size() / 2;
        return ordered.size() % 2 == 0 ? round((ordered.get(middle - 1) + ordered.get(middle)) / 2.0) : ordered.get(middle);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Map<String, Object> map) {
        return map != null ? map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String string(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @GetMapping("/drift-status")
    public ResponseEntity<Map<String, Object>> driftStatus() {
        return ResponseEntity.ok(driftMonitorService.status());
    }

    @PostMapping("/trigger-retrain")
    public ResponseEntity<Map<String, Object>> triggerRetrain(
            @RequestParam(required = false) String stationKey,
            @RequestParam(required = false, defaultValue = "STATION") String modelScope,
            @RequestParam(required = false, defaultValue = "24") Integer horizonHours,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(driftMonitorService.triggerRetrain(stationKey, modelScope, horizonHours, reason));
    }

    @GetMapping("/retraining-requests")
    public ResponseEntity<Map<String, Object>> retrainingRequests() {
        return ResponseEntity.ok(driftMonitorService.retrainingRequests());
    }
}
