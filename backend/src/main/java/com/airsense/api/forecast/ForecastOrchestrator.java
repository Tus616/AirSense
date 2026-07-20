package com.airsense.api.forecast;

import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionAttributionService;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.entities.AqiForecastRun;
import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.entities.ForecastModelRegistryEntry;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import com.airsense.api.history.CanonicalLocationIdentity;
import com.airsense.api.history.CanonicalLocationIdentityService;
import com.airsense.api.history.DataOrigin;
import com.airsense.api.history.HistoricalAirQualityIngestionService;
import com.airsense.api.repositories.AqiForecastRunRepository;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import com.airsense.api.repositories.ForecastModelRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service("hyperlocalForecastOrchestrator")
@RequiredArgsConstructor
public class ForecastOrchestrator {
    private static final List<Integer> HORIZONS = List.of(24, 48, 72);

    private final DataFusionService dataFusionService;
    private final FeatureBuilder featureBuilder;
    private final ForecastExplainer forecastExplainer;
    private final PollutionAttributionService attributionService;
    private final CanonicalLocationIdentityService locationIdentityService;

    @Autowired(required = false)
    private AqiHistoricalSnapshotRepository historicalSnapshotRepository;
    @Autowired(required = false)
    private AqiForecastRunRepository forecastRunRepository;
    @Autowired(required = false)
    private HistoricalAirQualityIngestionService ingestionService;
    @Autowired(required = false)
    private ForecastEngineProperties configuredProperties;
    @Autowired(required = false)
    private MlForecastClient mlForecastClient;
    @Autowired(required = false)
    private ForecastModelRegistryRepository modelRegistryRepository;
    @Autowired(required = false)
    private MlForecastProperties configuredMlProperties;

    public ForecastResult forecast(ForecastRequest request) {
        ForecastRequest normalized = (request != null ? request : ForecastRequest.builder().build()).normalized();
        CityEnvironmentalContext context = dataFusionService.buildContext(FusionRequest.builder()
                .cityId(normalized.getCityId())
                .placeId(normalized.getPlaceId())
                .cityName(normalized.getCityName())
                .state(normalized.getState())
                .country(normalized.getCountry())
                .latitude(normalized.getLatitude())
                .longitude(normalized.getLongitude())
                .build());
        if (ingestionService != null && normalized.getLatitude() != null && normalized.getLongitude() != null) {
            ingestionService.saveFromContext(locationRequest(normalized), context);
        }
        return forecast(context, normalized);
    }

    public ForecastResult forecast(CityEnvironmentalContext context, ForecastRequest request) {
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(null);
        ForecastRequest safeRequest = (request != null ? request : ForecastRequest.builder().build()).normalized();
        AttributionResult attribution = attributionService.attribute(safeContext, AttributionRequest.builder()
                .cityId(safeRequest.getCityId())
                .placeId(safeRequest.getPlaceId())
                .cityName(safeRequest.getCityName())
                .state(safeRequest.getState())
                .country(safeRequest.getCountry())
                .wardId(safeRequest.getWardId())
                .latitude(safeRequest.getLatitude())
                .longitude(safeRequest.getLongitude())
                .build());
        return forecast(safeContext, safeRequest, attribution);
    }

    public ForecastResult forecast(CityEnvironmentalContext context, ForecastRequest request, AttributionResult attribution) {
        ForecastEngineProperties properties = properties();
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(null);
        ForecastRequest safeRequest = (request != null ? request : ForecastRequest.builder().build()).normalized();
        Map<String, Object> aqi = safeMap(safeContext.getAqi());
        Map<String, Object> selected = asMap(aqi.get("selected"));
        Integer currentAqi = integer(first(selected.get("currentAqi"), aqi.get("currentAqi")));
        String forecastStandard = valueOrDefault(string(first(selected.get("standard"), aqi.get("standard"))), "");
        String currentProvider = valueOrDefault(string(first(selected.get("provider"), aqi.get("provider"))), "UNAVAILABLE");
        CanonicalLocationIdentity identity = locationIdentity(safeRequest, safeContext);
        String locationKey = identity.locationKey();
        Instant generatedAt = Instant.now();

        // Extract CPCB station identity — the station's own coordinates may differ from searched coordinates
        Double stationLat = number(selected.get("stationLatitude"));
        Double stationLon = number(selected.get("stationLongitude"));
        String selectedStationName = string(selected.get("stationName"));
        String stationLocationKey = stationLat != null && stationLon != null
                ? locationIdentityService.identity(null, null, identity.countryCode(), stationLat, stationLon).locationKey()
                : locationKey;
        String stationKey = selectedStationName != null && !selectedStationName.isBlank()
                ? selectedStationName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "")
                : null;
        log.debug("ForecastOrchestrator searchedLocationKey={} stationLocationKey={} stationKey={} stationName={}",
                locationKey, stationLocationKey, stationKey, selectedStationName);

        if (currentAqi == null || currentAqi <= 0 || forecastStandard.isBlank()) {
            return unavailable(safeContext, safeRequest, generatedAt, locationKey, currentAqi, forecastStandard, currentProvider,
                    List.of("CURRENT_AQI_UNAVAILABLE"));
        }

        List<AqiHistoricalSnapshot> history = sameStandardHistory(identity, stationLocationKey, stationKey, forecastStandard, Math.max(168, properties.getHistoryWindowHours()), safeContext, currentAqi);
        List<AqiHistoricalSnapshot> orderedHistory = dedupeAndSort(history);
        List<String> warnings = new ArrayList<>();
        if ("IQAIR".equalsIgnoreCase(currentProvider)) {
            warnings.add("FALLBACK_STANDARD_FORECAST_US_AQI");
        }

        Map<String, Object> weather = safeMap(safeContext.getWeather());
        List<Map<String, Object>> futureWeather = futureWeather(weather);
        if (futureWeather.isEmpty()) warnings.add("FUTURE_WEATHER_UNAVAILABLE");
        boolean currentFresh = currentDataFresh(currentAqi, aqi, properties);
        if (!currentFresh) warnings.add("STALE_CURRENT_OBSERVATION");

        ForecastFeatures features = featureBuilder.build(safeContext, safeRequest,
                attribution != null ? attribution.getDominantSource() : PollutionSourceType.UNKNOWN);
        Map<String, ForecastPoint> baseline = persistenceBaseline(currentAqi, forecastStandard, generatedAt, features, properties, currentProvider,
                orderedHistory, futureWeather, currentFresh);

        Map<String, ForecastPoint> trendForecast = trendWeatherForecast(currentAqi, forecastStandard, generatedAt, features,
                orderedHistory, futureWeather, properties, currentProvider, currentFresh);
        Map<String, ForecastPoint> mlForecast = promotedModelForecast(safeContext, safeRequest, currentAqi, forecastStandard,
                generatedAt, locationKey, stationLocationKey, stationKey, selectedStationName, stationLat, stationLon,
                features, baseline, orderedHistory, futureWeather, currentFresh);
        Map<String, ForecastPoint> forecast = new LinkedHashMap<>();
        for (Integer horizon : HORIZONS) {
            ForecastPoint mlPoint = mlForecast.get(horizon + "h");
            ForecastPoint trendPoint = trendForecast.get(horizon + "h");
            ForecastPoint baselinePoint = baseline.get(horizon + "h");
            ForecastPoint selectedPoint = mlPoint != null && mlPoint.getPredictedAqi() != null
                    ? mlPoint
                    : trendPoint != null && trendPoint.isSufficientHistory()
                    ? trendPoint
                    : baselinePoint;
            if (selectedPoint != null && mlPoint != null && selectedPoint != mlPoint && mlPoint.getFallbackReason() != null) {
                selectedPoint.setOodStatus(mlPoint.getOodStatus());
                selectedPoint.setOodScore(mlPoint.getOodScore());
                selectedPoint.setOodLevel(mlPoint.getOodLevel());
                selectedPoint.setOodFeatures(mlPoint.getOodFeatures());
                selectedPoint.setModelWarnings(mlPoint.getModelWarnings());
                selectedPoint.setTrainingDeltaPercentiles(mlPoint.getTrainingDeltaPercentiles());
                selectedPoint.setFeatureDiagnostics(mlPoint.getFeatureDiagnostics());
                selectedPoint.setModelContributions(mlPoint.getModelContributions());
                selectedPoint.setPredictedDelta(mlPoint.getPredictedDelta());
                if ("OUT_OF_DISTRIBUTION_FEATURES".equals(mlPoint.getFallbackReason())) {
                    selectedPoint.setFallbackReason(mlPoint.getFallbackReason());
                }
            }
            forecast.put(horizon + "h", selectedPoint);
            if (mlPoint != null && mlPoint.getFallbackReason() != null) {
                warnings.add(mlPoint.getFallbackReason());
            }
            if (baselinePoint != null && !baselinePoint.isSufficientHistory()) {
                warnings.addAll(baselinePoint.getInsufficiencyReasons());
            }
        }
        enforceNonIncreasingConfidence(forecast);

        String finalStationName = !selectedStationName.isBlank() ? selectedStationName
                : orderedHistory.isEmpty() ? null : orderedHistory.get(0).getStationName();
        String forecastSnapshotId = snapshotId(safeContext);
        double totalFeatures = features.getHistoryCount() > 0 ? Math.min(100.0, 100.0 * Math.min(orderedHistory.size(), 168) / 168.0) : 0.0;
        for (ForecastPoint p : forecast.values()) {
            if (p != null) {
                p.setStationName(finalStationName);
                p.setStationKey(stationKey);
                p.setStationLocationKey(stationLocationKey);
                p.setSnapshotId(forecastSnapshotId);
                p.setAqiStandard(forecastStandard);
                if (p.getForecastScope() == null || p.getForecastScope().isBlank() || "STATION".equals(p.getForecastScope())) {
                    p.setForecastScope(p.getMode() != null && p.getMode().startsWith("ML_")
                            ? "STATION_SPECIFIC_ML"
                            : "TREND_WEATHER_V1".equals(p.getMode())
                                    ? "TREND_WEATHER_V1"
                                    : "PERSISTENCE_FALLBACK");
                }
                p.setHistoryObservationCount(orderedHistory.size());
                p.setHistoryCoverageHours(round(coverageHours(orderedHistory)));
                p.setFeatureCoveragePercent(round(totalFeatures));
                if (p.getPromotionStatus() == null) {
                    p.setPromotionStatus(p.getModelPromotionStatus() != null ? p.getModelPromotionStatus() : "NOT_APPLICABLE");
                }
            }
        }

        String engine = resultEngine(forecast);
        String modelVersion = engine.startsWith("ML_") ? forecast.values().stream()
                .filter(point -> point.getMode() != null && point.getMode().startsWith("ML_"))
                .map(ForecastPoint::getModelVersion)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("promoted-ml")
                : "TREND_WEATHER_V1".equals(engine) ? properties.getModelVersion()
                : "PERSISTENCE_FALLBACK".equals(engine) ? "persistence-v1" : "mixed-horizon-v1";
        double overallConfidence = forecast.values().stream().mapToDouble(ForecastPoint::getConfidence).average().orElse(0.0);
        String overallTrend = forecast.getOrDefault("72h", forecast.values().stream().findFirst().orElse(ForecastPoint.builder().trend("UNAVAILABLE").build())).getTrend();

        ForecastResult result = ForecastResult.builder()
                .city(valueOrDefault(safeContext.getCity(), safeRequest.getCityName()))
                .cityId(valueOrDefault(safeContext.getCityId(), safeRequest.getCityId()))
                .wardId(safeRequest.getWardId())
                .generatedAt(generatedAt)
                .snapshotId(forecastSnapshotId)
                .locationHash(locationKey)
                .location(locationMap(safeRequest, identity))
                .currentAqi(currentAqi)
                .forecastStandard(forecastStandard)
                .currentProvider(currentProvider)
                .engine(engine)
                .stationKey(stationKey)
                .stationName(finalStationName)
                .stationLocationKey(stationLocationKey)
                .dataWindowHours(properties.getHistoryWindowHours())
                .observationCount(orderedHistory.size())
                .dataQuality(dataQuality(orderedHistory.size(), futureWeather, warnings))
                .forecast(forecast)
                .baseline(baseline)
                .overallConfidence(round(overallConfidence))
                .overallTrend(overallTrend)
                .fallbackUsed(forecast.values().stream().anyMatch(ForecastPoint::isFallbackUsed))
                .modelVersion(modelVersion)
                .mode(engine)
                .formulaVersion(modelVersion)
                .providerStatus(safeContext.getProviderStatus() != null ? safeContext.getProviderStatus() : Map.of())
                .warnings(warnings.stream().distinct().toList())
                .build();
        storeForecastRuns(result, locationKey);
        log.info("ForecastOrchestrator Success locationKey={} engine={} standard={} observations={} confidence={}",
                locationKey, engine, forecastStandard, orderedHistory.size(), result.getOverallConfidence());
        return result;
    }

    private Map<String, ForecastPoint> persistenceBaseline(Integer currentAqi, String standard, Instant generatedAt,
                                                           ForecastFeatures features, ForecastEngineProperties properties,
                                                           String currentProvider, List<AqiHistoricalSnapshot> history,
                                                           List<Map<String, Object>> futureWeather, boolean currentFresh) {
        Map<String, ForecastPoint> points = new LinkedHashMap<>();
        for (Integer horizon : HORIZONS) {
            HorizonAssessment assessment = assessHorizon(horizon, history, futureWeather, currentFresh, properties);
            double confidence = confidence(properties, assessment, horizon, !futureWeather.isEmpty(), currentProvider,
                    assessment.reasons(), false, standardDeviation(history.stream().map(AqiHistoricalSnapshot::getCurrentAqi).filter(Objects::nonNull).toList()));
            int band = uncertaintyBand(List.of(currentAqi), confidence, horizon);
            ForecastPoint point = point(horizon, generatedAt, currentAqi, currentAqi, band, "STABLE", confidence,
                    "PERSISTENCE", "persistence-v1", true, features, List.of(driver("LATEST_SAME_STANDARD_AQI", "NEUTRAL",
                            "Predicted AQI equals the latest valid " + standard + " observation")));
            applyAssessment(point, assessment, DataOrigin.PERSISTENCE_BASELINE.name());
            points.put(horizon + "h", point);
        }
        return points;
    }

    private Map<String, ForecastPoint> trendWeatherForecast(Integer currentAqi, String standard, Instant generatedAt,
                                                            ForecastFeatures features, List<AqiHistoricalSnapshot> history,
                                                            List<Map<String, Object>> futureWeather,
                                                            ForecastEngineProperties properties, String currentProvider,
                                                            boolean currentFresh) {
        Map<String, ForecastPoint> points = new LinkedHashMap<>();
        double slopePerHour = robustSlopePerHour(history);
        List<Integer> recentValues = history.stream().map(AqiHistoricalSnapshot::getCurrentAqi).filter(Objects::nonNull).toList();
        for (Integer horizon : HORIZONS) {
            HorizonAssessment assessment = assessHorizon(horizon, history, futureWeather, currentFresh, properties);
            if (!properties.isEnableTrendWeatherEngine() || !assessment.sufficient()) {
                continue;
            }
            WeatherAdjustment weatherAdjustment = weatherAdjustment(futureWeather, horizon, properties);
            double raw = currentAqi + cap(slopePerHour * horizon, -40, 40) + weatherAdjustment.adjustment();
            int predicted = clampAqi((int) Math.round(raw), standard);
            double confidence = confidence(properties, assessment, horizon, !futureWeather.isEmpty(), currentProvider,
                    weatherAdjustment.warnings(), true, standardDeviation(recentValues));
            int band = uncertaintyBand(recentValues, confidence, horizon);
            String trend = trend(currentAqi, predicted);
            List<Map<String, String>> drivers = new ArrayList<>(weatherAdjustment.drivers());
            if (Math.abs(slopePerHour) > 0.35) {
                drivers.add(driver("RECENT_AQI_TREND", slopePerHour > 0 ? "INCREASE" : "DECREASE",
                        "Robust recent AQI slope " + round(slopePerHour) + " per hour"));
            }
            ForecastPoint point = point(horizon, generatedAt, predicted, currentAqi, band, trend, confidence,
                    "TREND_WEATHER_V1", properties.getModelVersion(), false, features, drivers);
            applyAssessment(point, assessment, DataOrigin.FORECAST.name());
            points.put(horizon + "h", point);
        }
        return points;
    }

    private Map<String, ForecastPoint> promotedModelForecast(CityEnvironmentalContext context, ForecastRequest request,
                                                             Integer currentAqi, String standard, Instant generatedAt,
                                                             String locationKey, String stationLocationKey, String stationKey,
                                                             String stationName, Double stationLat, Double stationLon,
                                                             ForecastFeatures features,
                                                             Map<String, ForecastPoint> baseline,
                                                             List<AqiHistoricalSnapshot> history,
                                                             List<Map<String, Object>> futureWeather,
                                                             boolean currentFresh) {
        MlForecastProperties mlProperties = mlProperties();
        if (!mlProperties.isEnabled() || mlForecastClient == null || modelRegistryRepository == null) {
            return Map.of();
        }
        // Query registry by the CPCB station's own locationKey or stationKey (not the searched one)
        Map<Integer, ForecastModelRegistryEntry> registryEntries = new LinkedHashMap<>();
        Map<Integer, List<String>> candidateScopesByHorizon = new LinkedHashMap<>();
        String registryLookupKey = stationKey != null ? stationKey : stationLocationKey;
        for (Integer horizon : HORIZONS) {
            List<String> candidateScopes = candidateModelScopes(registryLookupKey, history);
            candidateScopesByHorizon.put(horizon, candidateScopes);
            for (String scope : candidateScopes) {
                Optional<ForecastModelRegistryEntry> promoted = modelRegistryRepository
                        .findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                                standard, horizon, scope)
                        .filter(entry -> "PROMOTED".equalsIgnoreCase(valueOrDefault(entry.getPromotionStatus(), "")));
                if (promoted.isPresent()) {
                    registryEntries.put(horizon, promoted.get());
                    break;
                }
            }
        }
        if (registryEntries.isEmpty()) {
            log.debug("No promoted models found for registryLookupKey={} standard={}", registryLookupKey, standard);
            return Map.of();
        }
        log.info("Found {} promoted ML models for registryLookupKey={}", registryEntries.size(), registryLookupKey);

        List<Map<String, Object>> mappedHistory = history.stream().map(h -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", h.getProviderObservedAt() != null ? h.getProviderObservedAt().toString() : null);
            map.put("currentAqi", h.getCurrentAqi());
            map.put("aqiStandard", valueOrDefault(h.getAqiStandard(), standard));
            map.put("locationKey", valueOrDefault(h.getStationKey(), valueOrDefault(h.getStationLocationKey(), h.getLocationKey())));
            map.put("stationKey", h.getStationKey());
            map.put("stationLocationKey", h.getStationLocationKey());
            return map;
        }).toList();

        MlForecastClient.MlForecastRequest mlRequest = MlForecastClient.MlForecastRequest.builder()
                .snapshotId(snapshotId(context))
                .locationKey(locationKey)
                .stationLocationKey(stationLocationKey)
                .stationKey(stationKey)
                .forecastScope("ORDERED_RUNTIME_SELECTION")
                .candidateModelScopes(orderedRequestScopes(candidateScopesByHorizon, registryEntries))
                .stationName(stationName)
                .stationLatitude(stationLat)
                .stationLongitude(stationLon)
                .forecastStandard(standard)
                .currentAqi(currentAqi)
                .horizons(new ArrayList<>(registryEntries.keySet()))
                .features(modelFeatureMap(context, request, currentAqi, history, futureWeather))
                .history(mappedHistory)
                .build();
        return mlForecastClient.predict(mlRequest)
                .map(response -> mlForecastPoints(response, registryEntries, generatedAt, currentAqi, standard, features, baseline))
                .orElseGet(() -> mlUnavailablePoints(registryEntries.keySet(), "ML_SERVICE_UNAVAILABLE"));
    }

    private Map<String, ForecastPoint> mlForecastPoints(MlForecastClient.MlForecastResponse response,
                                                        Map<Integer, ForecastModelRegistryEntry> registryEntries,
                                                        Instant generatedAt, Integer currentAqi, String standard,
                                                        ForecastFeatures features,
                                                        Map<String, ForecastPoint> baseline) {
        Map<String, ForecastPoint> points = new LinkedHashMap<>();
        Map<Integer, MlForecastClient.MlForecastPrediction> predictions = new LinkedHashMap<>();
        if (response.getPredictions() != null) {
            response.getPredictions().stream()
                    .filter(prediction -> prediction.getHorizonHours() != null)
                    .forEach(prediction -> predictions.put(prediction.getHorizonHours(), prediction));
        }
        for (Map.Entry<Integer, ForecastModelRegistryEntry> entry : registryEntries.entrySet()) {
            Integer horizon = entry.getKey();
            MlForecastClient.MlForecastPrediction prediction = predictions.get(horizon);
            if (prediction == null || !"PROMOTED".equalsIgnoreCase(valueOrDefault(prediction.getStatus(), ""))
                    || prediction.getPredictedAqi() == null) {
                String reason = prediction != null ? valueOrDefault(prediction.getFallbackReason(), prediction.getStatus()) : "ML_RESPONSE_MISSING";
                ForecastPoint unavailable = mlUnavailablePoint(horizon, generatedAt, reason);
                if (prediction != null) {
                    unavailable.setPredictedDelta(prediction.getPredictedDelta());
                    unavailable.setOodStatus(prediction.getOodStatus());
                    unavailable.setOodScore(prediction.getOodScore());
                    unavailable.setOodLevel(prediction.getOodLevel());
                    unavailable.setOodFeatures(prediction.getOodFeatures() != null ? prediction.getOodFeatures() : List.of());
                    unavailable.setModelWarnings(prediction.getWarnings() != null ? prediction.getWarnings() : List.of());
                    unavailable.setTrainingDeltaPercentiles(prediction.getTrainingDeltaPercentiles() != null ? prediction.getTrainingDeltaPercentiles() : Map.of());
                    unavailable.setFeatureDiagnostics(prediction.getFeatureDiagnostics() != null ? prediction.getFeatureDiagnostics() : Map.of());
                    unavailable.setModelContributions(prediction.getModelContributions() != null ? prediction.getModelContributions() : Map.of());
                }
                points.put(horizon + "h", unavailable);
                continue;
            }
            String forecastScope = valueOrDefault(prediction.getForecastScope(),
                    "GLOBAL".equalsIgnoreCase(entry.getValue().getModelScope()) ? "MULTI_STATION_GLOBAL_ML" : "STATION_SPECIFIC_ML");
            String mlEngine = valueOrDefault(prediction.getEngine(), mlEngineLabel(entry.getValue().getModelFamily(), forecastScope));
            int predicted = clampAqi(prediction.getPredictedAqi(), standard);
            double confidence = prediction.getConfidence() != null ? prediction.getConfidence() : 0.45;
            int fallbackBand = uncertaintyBand(List.of(currentAqi), confidence, horizon);
            ForecastPoint point = point(horizon, generatedAt, predicted, currentAqi, fallbackBand, trend(currentAqi, predicted),
                    confidence, mlEngine, valueOrDefault(prediction.getModelVersion(), entry.getValue().getVersion()),
                    false, features, List.of(driver("PROMOTED_MODEL", "PREDICT",
                            "Active registry-promoted model evaluated against persistence baseline")));
            point.setLowerBound(prediction.getLowerBound() != null ? clampAqi(prediction.getLowerBound(), standard) : point.getLowerBound());
            point.setUpperBound(prediction.getUpperBound() != null ? clampAqi(prediction.getUpperBound(), standard) : point.getUpperBound());
            point.setEngine(mlEngine);
            point.setModelPromotionStatus("PROMOTED");
            point.setForecastScope(forecastScope);
            point.setModelFamily(valueOrDefault(prediction.getModelFamily(), entry.getValue().getModelFamily()));
            point.setBaselinePredictedAqi(prediction.getBaselinePredictedAqi() != null
                    ? prediction.getBaselinePredictedAqi()
                    : baselinePredictedAqi(baseline, horizon));
            point.setValidationRmse(prediction.getValidationRmse());
            point.setBaselineRmse(prediction.getBaselineRmse());
            point.setPredictedDelta(prediction.getPredictedDelta());
            point.setUnclampedPredictedAqi(prediction.getUnclampedPredictedAqi());
            point.setOodStatus(valueOrDefault(prediction.getOodStatus(), "UNKNOWN"));
            point.setOodScore(prediction.getOodScore());
            point.setOodLevel(prediction.getOodLevel());
            point.setOodFeatures(prediction.getOodFeatures() != null ? prediction.getOodFeatures() : List.of());
            point.setModelWarnings(prediction.getWarnings() != null ? prediction.getWarnings() : List.of());
            point.setTrainingDeltaPercentiles(prediction.getTrainingDeltaPercentiles() != null ? prediction.getTrainingDeltaPercentiles() : Map.of());
            point.setFeatureDiagnostics(prediction.getFeatureDiagnostics() != null ? prediction.getFeatureDiagnostics() : Map.of());
            point.setModelContributions(prediction.getModelContributions() != null ? prediction.getModelContributions() : Map.of());
            point.setConfidenceLabel(valueOrDefault(prediction.getConfidenceLabel(), confidenceLabel(confidence)));
            point.setDataOrigin(DataOrigin.FORECAST.name());
            point.setSufficientHistory(true);
            points.put(horizon + "h", point);
        }
        return points;
    }

    private Map<String, ForecastPoint> mlUnavailablePoints(Iterable<Integer> horizons, String reason) {
        Map<String, ForecastPoint> points = new LinkedHashMap<>();
        for (Integer horizon : horizons) {
            points.put(horizon + "h", mlUnavailablePoint(horizon, Instant.now(), reason));
        }
        return points;
    }

    private List<String> candidateModelScopes(String stationScope, List<AqiHistoricalSnapshot> history) {
        List<String> scopes = new ArrayList<>();
        if (stationScope != null && !stationScope.isBlank()) {
            scopes.add(stationScope);
        }
        HistoryEligibility eligibility = historyEligibility(history);
        if (eligibility.fullHistory()) {
            scopes.add("GLOBAL_FULL_HISTORY");
        }
        if (eligibility.mediumHistory()) {
            scopes.add("GLOBAL_MEDIUM_HISTORY");
        }
        if (eligibility.shortHistory()) {
            scopes.add("GLOBAL_SHORT_HISTORY");
        }
        scopes.add("GLOBAL_COLD_START");
        scopes.add("GLOBAL");
        return scopes.stream().filter(scope -> scope != null && !scope.isBlank()).distinct().toList();
    }

    private List<String> orderedRequestScopes(Map<Integer, List<String>> candidatesByHorizon,
                                              Map<Integer, ForecastModelRegistryEntry> selectedEntries) {
        List<String> ordered = new ArrayList<>();
        for (Integer horizon : HORIZONS) {
            ForecastModelRegistryEntry entry = selectedEntries.get(horizon);
            if (entry != null && entry.getModelScope() != null) {
                ordered.add(entry.getModelScope());
            }
            ordered.addAll(candidatesByHorizon.getOrDefault(horizon, List.of()));
        }
        return ordered.stream().distinct().toList();
    }

    private HistoryEligibility historyEligibility(List<AqiHistoricalSnapshot> history) {
        if (history == null || history.isEmpty()) {
            return new HistoryEligibility(false, false, false);
        }
        List<Instant> times = history.stream()
                .map(AqiHistoricalSnapshot::getProviderObservedAt)
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
        if (times.size() < 2) {
            return new HistoryEligibility(false, false, false);
        }
        Instant latest = times.get(times.size() - 1);
        boolean shortOk = hasObservationNear(times, latest.minusSeconds(3600), 75)
                || hasObservationNear(times, latest.minusSeconds(3 * 3600L), 75)
                || hasObservationNear(times, latest.minusSeconds(6 * 3600L), 75)
                || hasObservationNear(times, latest.minusSeconds(12 * 3600L), 75)
                || hasObservationNear(times, latest.minusSeconds(24 * 3600L), 75);
        boolean mediumOk = contiguousCoverage(times, latest, 72, 6, 36);
        boolean fullOk = contiguousCoverage(times, latest, 168, 6, 120);
        return new HistoryEligibility(shortOk, mediumOk, fullOk);
    }

    private boolean contiguousCoverage(List<Instant> times, Instant latest, int hours, int maxGapHours, int minObservations) {
        Instant start = latest.minusSeconds(hours * 3600L);
        List<Instant> recent = times.stream().filter(time -> !time.isBefore(start)).toList();
        if (recent.size() < minObservations) {
            return false;
        }
        long spanHours = Duration.between(recent.get(0), recent.get(recent.size() - 1)).toHours();
        if (spanHours < Math.max(1, hours - maxGapHours)) {
            return false;
        }
        for (int i = 1; i < recent.size(); i++) {
            if (Duration.between(recent.get(i - 1), recent.get(i)).toHours() > maxGapHours) {
                return false;
            }
        }
        return true;
    }

    private boolean hasObservationNear(List<Instant> times, Instant target, long toleranceMinutes) {
        return times.stream().anyMatch(time -> Math.abs(Duration.between(time, target).toMinutes()) <= toleranceMinutes);
    }

    private record HistoryEligibility(boolean shortHistory, boolean mediumHistory, boolean fullHistory) {
    }

    private ForecastPoint mlUnavailablePoint(Integer horizon, Instant generatedAt, String reason) {
        ForecastPoint point = ForecastPoint.builder()
                .horizonHours(horizon)
                .forecastAt(generatedAt.plusSeconds(horizon * 3600L))
                .generatedAt(generatedAt)
                .targetTime(generatedAt.plusSeconds(horizon * 3600L))
                .mode("ML_PROMOTED")
                .engine("ML_PROMOTED")
                .modelPromotionStatus("UNAVAILABLE")
                .fallbackReason(ForecastFallbackReason.publicValue(valueOrDefault(reason, "MODEL_NOT_PROMOTED")))
                .dataOrigin(DataOrigin.UNAVAILABLE.name())
                .build();
        return point;
    }

    private Integer baselinePredictedAqi(Map<String, ForecastPoint> baseline, Integer horizon) {
        ForecastPoint point = baseline != null ? baseline.get(horizon + "h") : null;
        return point != null ? point.getPredictedAqi() : null;
    }

    private Map<String, Object> modelFeatureMap(CityEnvironmentalContext context, ForecastRequest request, Integer currentAqi,
                                                List<AqiHistoricalSnapshot> history,
                                                List<Map<String, Object>> futureWeather) {
        Map<String, Object> features = new LinkedHashMap<>();
        Map<String, Object> aqi = safeMap(context.getAqi());
        Map<String, Object> selected = asMap(aqi.get("selected"));
        Map<String, Object> pollutants = asMap(first(selected.get("pollutants"), aqi.get("pollutants")));
        Map<String, Object> weather = safeMap(context.getWeather());
        Map<String, Object> coordinates = safeMap(context.getCoordinates());
        Object stationLatitude = first(selected.get("stationLatitude"), selected.get("latitude"));
        Object stationLongitude = first(selected.get("stationLongitude"), selected.get("longitude"));
        features.put("featureSchemaVersion", "forecasting-feature-schema-v2");
        features.put("currentAqi", currentAqi);
        features.put("latitude", first(request.getLatitude(), coordinates.get("latitude")));
        features.put("longitude", first(request.getLongitude(), coordinates.get("longitude")));
        features.put("stationLatitude", stationLatitude);
        features.put("stationLongitude", stationLongitude);
        features.put("stationLatitudeFeature", stationLatitude);
        features.put("stationLongitudeFeature", stationLongitude);
        features.put("pm25", first(pollutants.get("pm25"), pollutants.get("PM2.5")));
        features.put("pm10", first(pollutants.get("pm10"), pollutants.get("PM10")));
        features.put("no2", first(pollutants.get("no2"), pollutants.get("NO2")));
        features.put("so2", first(pollutants.get("so2"), pollutants.get("SO2")));
        features.put("co", first(pollutants.get("co"), pollutants.get("CO")));
        features.put("o3", first(pollutants.get("o3"), pollutants.get("O3")));
        features.put("nh3", first(pollutants.get("nh3"), pollutants.get("NH3")));
        features.put("weather_temperatureCelsius", first(weather.get("temperature"), weather.get("temperatureCelsius")));
        features.put("weather_humidityPercent", first(weather.get("humidity"), weather.get("humidityPercent")));
        features.put("weather_pressureHpa", first(weather.get("pressure"), weather.get("pressureHpa")));
        features.put("weather_windSpeedMps", first(weather.get("windSpeed"), weather.get("windSpeedMps")));
        features.put("weather_windDirectionDegrees", first(weather.get("windDirection"), weather.get("windDirectionDegrees")));
        features.put("weather_rainfallMm", first(weather.get("rainfall"), weather.get("rainfallMm")));
        features.put("historyCount", history != null ? history.size() : 0);
        features.put("futureWeatherCount", futureWeather != null ? futureWeather.size() : 0);
        return features;
    }

    private ForecastPoint point(int horizon, Instant generatedAt, Integer predictedAqi, Integer currentAqi, int band,
                                String trend, double confidence, String mode, String modelVersion, boolean fallbackUsed,
                                ForecastFeatures features, List<Map<String, String>> drivers) {
        return ForecastPoint.builder()
                .horizonHours(horizon)
                .forecastAt(generatedAt.plusSeconds(horizon * 3600L))
                .generatedAt(generatedAt)
                .targetTime(generatedAt.plusSeconds(horizon * 3600L))
                .predictedAqi(predictedAqi)
                .lowerBound(predictedAqi != null ? Math.max(0, predictedAqi - band) : null)
                .upperBound(predictedAqi != null ? Math.min(500, predictedAqi + band) : null)
                .aqiCategory(predictedAqi != null ? aqiCategory(predictedAqi) : "UNAVAILABLE")
                .confidence(round(confidence))
                .trend(trend)
                .modelVersion(modelVersion)
                .mode(mode)
                .engine(mode)
                .historyCount(features.getHistoryCount())
                .featureContributions(currentAqi != null ? Map.of("currentAqi", currentAqi.doubleValue()) : Map.of())
                .formulaVersion(modelVersion)
                .limitations(fallbackUsed ? List.of("Insufficient same-standard history for trend-weather engine.") : List.of())
                .dataWindow(features.getHistoryCount() > 0 ? features.getHistoryCount() + " observations" : "latest observation")
                .fallbackUsed(fallbackUsed)
                .expectedDominantSource(features.getAttributedDominantSource() != null ? features.getAttributedDominantSource() : PollutionSourceType.UNKNOWN)
                .meteorologicalInfluence(forecastExplainer.meteorologicalInfluence(features))
                .healthRiskLevel(predictedAqi != null ? healthRisk(predictedAqi) : "UNAVAILABLE")
                .confidenceBreakdown(ForecastConfidence.builder()
                        .modelConfidence(confidence)
                        .inputCompleteness(confidence)
                        .providerConfidence(confidence)
                        .horizonConfidence(confidence)
                        .dataFreshness(confidence)
                        .finalConfidence(round(confidence))
                        .build())
                .drivers(drivers)
                .confidenceLabel(confidenceLabel(confidence))
                .dataOrigin(predictedAqi != null ? DataOrigin.FORECAST.name() : DataOrigin.UNAVAILABLE.name())
                .build();
    }

    private ForecastResult unavailable(CityEnvironmentalContext context, ForecastRequest request, Instant generatedAt, String locationKey,
                                       Integer currentAqi, String standard, String provider, List<String> warnings) {
        Map<String, ForecastPoint> points = new LinkedHashMap<>();
        for (Integer horizon : HORIZONS) {
            ForecastPoint point = point(horizon, generatedAt, null, currentAqi, 0, "UNAVAILABLE", 0.0,
                    "UNAVAILABLE", "UNAVAILABLE", false, ForecastFeatures.builder().build(), List.of());
            point.setDataOrigin(DataOrigin.UNAVAILABLE.name());
            point.setInsufficiencyReasons(warnings);
            point.setConfidenceReductionReasons(warnings);
            points.put(horizon + "h", point);
        }
        return ForecastResult.builder()
                .city(request.getCityName())
                .cityId(request.getCityId())
                .generatedAt(generatedAt)
                .snapshotId(snapshotId(context))
                .locationHash(locationKey)
                .location(locationMap(request, locationIdentity(request, context)))
                .currentAqi(currentAqi)
                .forecastStandard(standard)
                .currentProvider(provider)
                .engine("UNAVAILABLE")
                .modelVersion("UNAVAILABLE")
                .mode("UNAVAILABLE")
                .formulaVersion("UNAVAILABLE")
                .forecast(points)
                .baseline(Map.of())
                .warnings(warnings)
                .overallConfidence(0.0)
                .overallTrend("UNAVAILABLE")
                .build();
    }

    private List<AqiHistoricalSnapshot> sameStandardHistory(CanonicalLocationIdentity identity, String stationLocationKey, String stationKey,
                                                            String standard, int hours,
                                                            CityEnvironmentalContext context, Integer currentAqi) {
        Instant end = Instant.now().plus(Duration.ofMinutes(Math.max(0, properties().getCurrentDataMaxAgeMinutes())));
        Instant start = end.minus(Duration.ofHours(hours));
        List<String> queryKeys = new ArrayList<>(locationIdentityService.queryKeys(identity));
        // Include the station's own locationKey so imported CPCB records are found
        // even when searched coordinates differ from station coordinates
        if (stationLocationKey != null && !queryKeys.contains(stationLocationKey)) {
            queryKeys.add(stationLocationKey);
        }
        if (stationKey != null && !queryKeys.contains(stationKey)) {
            queryKeys.add(stationKey);
        }
        List<AqiHistoricalSnapshot> history = historicalSnapshotRepository != null
                ? new ArrayList<>(historicalSnapshotRepository.findTop500ByLocationKeyInAndAqiStandardOrderByProviderObservedAtDesc(queryKeys, standard))
                : new ArrayList<>();
        if (history.isEmpty()) {
            history.addAll(contextHistory(identity.locationKey(), standard, context));
        }
        if (history.isEmpty() && currentAqi != null) {
            history.add(AqiHistoricalSnapshot.builder()
                    .locationKey(identity.locationKey())
                    .locationKeyVersion(identity.keyVersion())
                    .canonicalLocationKey(identity.locationKey())
                    .currentAqi(currentAqi)
                    .aqiStandard(standard)
                    .provider("CURRENT_CONTEXT")
                    .providerObservedAt(Instant.now())
                    .dataOrigin(DataOrigin.OBSERVED.name())
                    .dataQualityStatus("PARTIAL")
                    .build());
        }
        return history;
    }

    private List<AqiHistoricalSnapshot> contextHistory(String locationKey, String standard, CityEnvironmentalContext context) {
        if (context == null || context.getHistoricalAQI() == null || context.getHistoricalAQI().isEmpty()) {
            return List.of();
        }
        List<AqiHistoricalSnapshot> history = new ArrayList<>();
        Instant fallbackTimestamp = Instant.now().minus(Duration.ofHours(context.getHistoricalAQI().size()));
        for (int index = 0; index < context.getHistoricalAQI().size(); index++) {
            Map<String, Object> row = safeMap(context.getHistoricalAQI().get(index));
            Integer aqi = integer(first(row.get("aqi"), row.get("currentAqi")));
            if (aqi == null || aqi <= 0) continue;
            String rowStandard = valueOrDefault(string(row.get("aqiStandard")), standard);
            if (!standard.equalsIgnoreCase(rowStandard)) continue;
            Instant observedAt = instant(first(row.get("providerObservedAt"), row.get("observedAt"), row.get("timestamp")));
            history.add(AqiHistoricalSnapshot.builder()
                    .locationKey(locationKey)
                    .currentAqi(aqi)
                    .aqiStandard(standard)
                    .provider(valueOrDefault(string(row.get("provider")), "CONTEXT_HISTORY"))
                    .providerObservedAt(observedAt != null ? observedAt : fallbackTimestamp.plus(Duration.ofHours(index)))
                    .dataQualityStatus("PARTIAL")
                    .build());
        }
        return history;
    }

    private List<AqiHistoricalSnapshot> dedupeAndSort(List<AqiHistoricalSnapshot> history) {
        Map<Instant, AqiHistoricalSnapshot> deduped = new LinkedHashMap<>();
        history.stream()
                .filter(item -> item.getCurrentAqi() != null && item.getCurrentAqi() > 0 && item.getProviderObservedAt() != null)
                .sorted(Comparator.comparing(AqiHistoricalSnapshot::getProviderObservedAt))
                .forEach(item -> deduped.put(item.getProviderObservedAt(), item));
        return new ArrayList<>(deduped.values());
    }

    private HorizonAssessment assessHorizon(int horizon, List<AqiHistoricalSnapshot> history,
                                            List<Map<String, Object>> futureWeather,
                                            boolean currentFresh,
                                            ForecastEngineProperties properties) {
        int validCount = history.size();
        double coverage = coverageHours(history);
        double largestGap = largestGapHours(history);
        int requiredCount = properties.requiredObservations(horizon);
        int requiredCoverage = properties.requiredCoverageHours(horizon);
        List<String> reasons = new ArrayList<>();
        if (validCount < requiredCount) reasons.add("INSUFFICIENT_OBSERVATION_COUNT");
        if (coverage < requiredCoverage) reasons.add("INSUFFICIENT_TIME_COVERAGE");
        if (validCount < requiredCount || coverage < requiredCoverage) reasons.add("INSUFFICIENT_SAME_STANDARD_HISTORY");
        if (largestGap > properties.getMaxObservationGapHours()) reasons.add("EXCESSIVE_DATA_GAPS");
        if (!currentFresh) reasons.add("STALE_CURRENT_OBSERVATION");
        if (futureWeather.isEmpty()) reasons.add("FUTURE_WEATHER_UNAVAILABLE");
        return new HorizonAssessment(
                reasons.isEmpty(),
                validCount,
                round(coverage),
                round(largestGap),
                requiredCount,
                requiredCoverage,
                reasons.stream().distinct().toList()
        );
    }

    private void applyAssessment(ForecastPoint point, HorizonAssessment assessment, String dataOrigin) {
        point.setSufficientHistory(assessment.sufficient());
        point.setValidObservationCount(assessment.validObservationCount());
        point.setCoverageHours(assessment.coverageHours());
        point.setRequiredObservationCount(assessment.requiredObservationCount());
        point.setRequiredCoverageHours(assessment.requiredCoverageHours());
        point.setInsufficiencyReasons(assessment.reasons());
        point.setConfidenceReductionReasons(assessment.reasons());
        if (!assessment.sufficient()) {
            point.setFallbackReason(ForecastFallbackReason.publicValue(assessment.reasons()));
        }
        point.setHistoryCount(assessment.validObservationCount());
        point.setDataWindow(assessment.validObservationCount() + " observations / " + assessment.coverageHours() + " hours");
        point.setDataOrigin(dataOrigin);
    }

    private double coverageHours(List<AqiHistoricalSnapshot> history) {
        if (history.size() < 2) return 0.0;
        Instant first = history.get(0).getProviderObservedAt();
        Instant last = history.get(history.size() - 1).getProviderObservedAt();
        return first != null && last != null ? Duration.between(first, last).toMinutes() / 60.0 : 0.0;
    }

    private double largestGapHours(List<AqiHistoricalSnapshot> history) {
        double largest = 0.0;
        for (int i = 1; i < history.size(); i++) {
            Instant previous = history.get(i - 1).getProviderObservedAt();
            Instant current = history.get(i).getProviderObservedAt();
            if (previous != null && current != null) {
                largest = Math.max(largest, Duration.between(previous, current).toMinutes() / 60.0);
            }
        }
        return largest;
    }

    private double robustSlopePerHour(List<AqiHistoricalSnapshot> history) {
        if (history.size() < 2) return 0.0;
        AqiHistoricalSnapshot first = history.get(0);
        AqiHistoricalSnapshot last = history.get(history.size() - 1);
        double hours = Math.max(1.0, Duration.between(first.getProviderObservedAt(), last.getProviderObservedAt()).toMinutes() / 60.0);
        return cap((last.getCurrentAqi() - first.getCurrentAqi()) / hours, -3.0, 3.0);
    }

    private WeatherAdjustment weatherAdjustment(List<Map<String, Object>> futureWeather, int horizon, ForecastEngineProperties properties) {
        if (futureWeather.isEmpty()) return new WeatherAdjustment(0.0, List.of(), List.of("FUTURE_WEATHER_UNAVAILABLE"));
        List<Map<String, Object>> window = futureWeather.stream()
                .filter(row -> hoursUntil(row.get("timestamp")) <= horizon)
                .toList();
        if (window.isEmpty()) window = futureWeather;
        double avgWind = average(window, "windSpeed");
        double avgHumidity = average(window, "humidity");
        double rain = window.stream().mapToDouble(row -> number(row.get("rainfall"))).sum();
        double adjustment = 0.0;
        List<Map<String, String>> drivers = new ArrayList<>();
        if (avgWind > 0 && avgWind < properties.getLowWindThresholdMps()) {
            adjustment += properties.getLowWindIncreasePerDay() * horizon / 24.0;
            drivers.add(driver("LOW_WIND_SPEED", "INCREASE", "Forecast wind speed below dispersion threshold"));
        } else if (avgWind >= properties.getHighWindThresholdMps()) {
            adjustment -= properties.getHighWindDecreasePerDay() * horizon / 24.0;
            drivers.add(driver("HIGH_WIND_SPEED", "DECREASE", "Forecast wind speed supports dispersion"));
        }
        if (rain > 0) {
            adjustment -= Math.min(25.0, rain * properties.getRainfallDecreasePerMm());
            drivers.add(driver("RAINFALL", "DECREASE", "Forecast rainfall may reduce particulate concentration"));
        }
        if (avgHumidity >= properties.getHighHumidityThresholdPercent()) {
            adjustment += properties.getHighHumidityIncreasePerDay() * horizon / 24.0;
            drivers.add(driver("HIGH_HUMIDITY", "INCREASE", "Forecast humidity is elevated"));
        }
        return new WeatherAdjustment(adjustment, drivers, List.of());
    }

    private int hoursUntil(Object timestamp) {
        try {
            return (int) Math.max(0, Duration.between(Instant.now(), Instant.parse(String.valueOf(timestamp))).toHours());
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> futureWeather(Map<String, Object> weather) {
        Object value = weather.get("hourlyForecast");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private double confidence(ForecastEngineProperties properties, HorizonAssessment assessment, int horizon, boolean weatherAvailable,
                              String provider, List<String> warnings, boolean trendMode, double volatility) {
        double obsScore = Math.min(1.0, assessment.validObservationCount() / (double) Math.max(1, properties.preferredObservations(horizon)));
        double coverageScore = Math.min(1.0, assessment.coverageHours() / Math.max(1.0, assessment.requiredCoverageHours()));
        double horizonPenalty = (horizon / 24.0 - 1.0) * 0.08;
        double confidence = (trendMode ? 0.52 : 0.34) + obsScore * 0.18 + coverageScore * 0.12 - horizonPenalty;
        if (!weatherAvailable) confidence -= 0.08;
        if ("IQAIR".equalsIgnoreCase(provider)) confidence -= 0.08;
        if (assessment.largestGapHours() > properties.getMaxObservationGapHours()) confidence -= 0.07;
        confidence -= Math.min(0.10, volatility / 500.0);
        confidence -= warnings.size() * 0.03;
        return Math.max(properties.getConfidenceFloor(), Math.min(0.92, confidence));
    }

    private void enforceNonIncreasingConfidence(Map<String, ForecastPoint> forecast) {
        double previous = 1.0;
        for (Integer horizon : HORIZONS) {
            ForecastPoint point = forecast.get(horizon + "h");
            if (point == null) continue;
            double adjusted = Math.min(point.getConfidence(), previous);
            point.setConfidence(round(adjusted));
            point.setConfidenceLabel(confidenceLabel(adjusted));
            if (point.getConfidenceBreakdown() != null) {
                point.getConfidenceBreakdown().setFinalConfidence(round(adjusted));
            }
            previous = adjusted;
        }
    }

    private String resultEngine(Map<String, ForecastPoint> forecast) {
        boolean anyMl = forecast.values().stream().anyMatch(point -> point.getMode() != null && point.getMode().startsWith("ML_"));
        boolean anyTrend = forecast.values().stream().anyMatch(point -> "TREND_WEATHER_V1".equals(point.getMode()));
        boolean anyPersistence = forecast.values().stream().anyMatch(ForecastPoint::isFallbackUsed);
        if (anyMl && (anyTrend || anyPersistence)) return "MIXED_HORIZON_MODES";
        if (anyMl) {
            // Return the specific ML engine label from the first ML point
            return forecast.values().stream()
                    .filter(p -> p.getMode() != null && p.getMode().startsWith("ML_"))
                    .map(ForecastPoint::getMode)
                    .findFirst()
                    .orElse("ML_PROMOTED");
        }
        if (anyTrend && anyPersistence) return "MIXED_HORIZON_MODES";
        if (anyTrend) return "TREND_WEATHER_V1";
        return "PERSISTENCE_FALLBACK";
    }

    private String mlEngineLabel(String modelFamily) {
        return mlEngineLabel(modelFamily, "MULTI_STATION_GLOBAL_ML");
    }

    private String mlEngineLabel(String modelFamily, String forecastScope) {
        String prefix = "STATION_SPECIFIC_ML".equalsIgnoreCase(forecastScope) ? "ML_STATION" : "ML_GLOBAL";
        if (modelFamily == null || modelFamily.isBlank()) return prefix + "_PROMOTED";
        return switch (modelFamily.toUpperCase(Locale.ROOT)) {
            case "XGBOOST" -> prefix + "_XGBOOST";
            case "SKLEARN_HIST_GRADIENT_BOOSTING" -> prefix + "_HIST_GRADIENT_BOOSTING";
            case "SKLEARN_RIDGE" -> prefix + "_RIDGE";
            default -> prefix + "_" + modelFamily.toUpperCase(Locale.ROOT);
        };
    }

    private String confidenceLabel(double confidence) {
        if (confidence >= 0.70) return "HIGH";
        if (confidence >= 0.45) return "MEDIUM";
        return "LOW";
    }

    private int uncertaintyBand(List<Integer> values, double confidence, int horizon) {
        double std = standardDeviation(values);
        double horizonFactor = horizon / 24.0;
        return Math.max(10, (int) Math.round(std + (1.0 - confidence) * 35 + horizonFactor * 7));
    }

    private double standardDeviation(List<Integer> values) {
        if (values.size() < 2) return 12.0;
        double mean = values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0);
        double variance = values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0.0);
        return Math.sqrt(variance);
    }

    private void storeForecastRuns(ForecastResult result, String locationKey) {
        if (forecastRunRepository == null) return;
        String runId = UUID.randomUUID().toString();
        result.getForecast().values().forEach(point -> forecastRunRepository.save(AqiForecastRun.builder()
                .forecastRunId(runId)
                .locationKey(locationKey)
                .snapshotId(result.getSnapshotId())
                .stationKey(result.getStationKey())
                .stationLocationKey(result.getStationLocationKey())
                .generatedAt(result.getGeneratedAt())
                .targetTime(point.getTargetTime())
                .horizonHours(point.getHorizonHours())
                .predictedAqi(point.getPredictedAqi())
                .lowerBound(point.getLowerBound())
                .upperBound(point.getUpperBound())
                .forecastStandard(result.getForecastStandard())
                .engine(point.getMode())
                .modelVersion(point.getModelVersion())
                .baselinePredictedAqi(result.getBaseline().get(point.getHorizonHours() + "h") != null
                        ? result.getBaseline().get(point.getHorizonHours() + "h").getPredictedAqi() : null)
                .evaluated(false)
                .build()));
    }

    private boolean currentDataFresh(Integer currentAqi, Map<String, Object> aqi, ForecastEngineProperties properties) {
        try {
            Instant observedAt = Instant.parse(String.valueOf(first(aqi.get("observedAt"), aqi.get("timestamp"))));
            return currentAqi != null && Duration.between(observedAt, Instant.now()).toMinutes() <= properties.getCurrentDataMaxAgeMinutes();
        } catch (Exception e) {
            return currentAqi != null;
        }
    }

    private String dataQuality(int observationCount, List<Map<String, Object>> futureWeather, List<String> warnings) {
        if (observationCount >= 48 && !futureWeather.isEmpty() && warnings.isEmpty()) return "HIGH";
        if (observationCount >= 12) return "MEDIUM";
        return "LOW";
    }

    private Map<String, Object> locationMap(ForecastRequest request, CanonicalLocationIdentity identity) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("locationKey", identity.locationKey());
        location.put("locationKeyVersion", identity.keyVersion());
        location.put("countryCode", identity.countryCode());
        location.put("roundedLatitude", identity.roundedLatitude());
        location.put("roundedLongitude", identity.roundedLongitude());
        location.put("legacyLocationKeys", identity.legacyLocationKeys());
        location.put("city", request.getCityName());
        location.put("state", request.getState());
        location.put("country", request.getCountry());
        location.put("latitude", request.getLatitude());
        location.put("longitude", request.getLongitude());
        return location;
    }

    private CanonicalLocationIdentity locationIdentity(ForecastRequest request, CityEnvironmentalContext context) {
        Double lat = request.getLatitude();
        Double lon = request.getLongitude();
        if ((lat == null || lon == null) && context.getCoordinates() != null) {
            lat = number(context.getCoordinates().get("latitude"));
            lon = number(context.getCoordinates().get("longitude"));
        }
        return locationIdentityService.identity(valueOrDefault(request.getCityName(), request.getCityId()), request.getState(), request.getCountry(), lat, lon);
    }

    private HistoricalAirQualityIngestionService.LocationRequest locationRequest(ForecastRequest request) {
        return new HistoricalAirQualityIngestionService.LocationRequest(
                valueOrDefault(request.getCityName(), request.getCityId()),
                valueOrDefault(request.getCityName(), request.getCityId()),
                request.getState(),
                request.getCountry(),
                request.getLatitude(),
                request.getLongitude());
    }

    private ForecastEngineProperties properties() {
        return configuredProperties != null ? configuredProperties : new ForecastEngineProperties();
    }

    private MlForecastProperties mlProperties() {
        return configuredMlProperties != null ? configuredMlProperties : new MlForecastProperties();
    }

    private String snapshotId(CityEnvironmentalContext context) {
        Object value = context != null && context.getMetadata() != null ? context.getMetadata().get("snapshotId") : null;
        return value != null ? String.valueOf(value) : "UNAVAILABLE";
    }

    private String trend(int current, int predicted) {
        int delta = predicted - current;
        if (delta >= 10) return "INCREASING";
        if (delta <= -10) return "DECREASING";
        return "STABLE";
    }

    private String aqiCategory(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Satisfactory";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "Poor";
        if (aqi <= 400) return "Very Poor";
        return "Severe";
    }

    private String healthRisk(int aqi) {
        if (aqi <= 100) return "Low";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "High";
        if (aqi <= 400) return "Very High";
        return "Severe";
    }

    private Map<String, String> driver(String factor, String effect, String evidence) {
        return Map.of("factor", factor, "effect", effect, "evidence", evidence);
    }

    private double average(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(row -> number(row.get(key))).filter(value -> value > 0).average().orElse(0.0);
    }

    private int clampAqi(int value, String standard) {
        return Math.max(0, Math.min(500, value));
    }

    private double cap(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Map<String, Object> map) {
        return map != null ? map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private Instant instant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer integer(Object value) {
        Double number = number(value);
        return number != null ? (int) Math.round(number) : null;
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

    private record WeatherAdjustment(double adjustment, List<Map<String, String>> drivers, List<String> warnings) {
    }

    private record HorizonAssessment(boolean sufficient,
                                     int validObservationCount,
                                     double coverageHours,
                                     double largestGapHours,
                                     int requiredObservationCount,
                                     double requiredCoverageHours,
                                     List<String> reasons) {
    }
}
