package com.airsense.api.temporal;

import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.DecisionIntelligenceService;
import com.airsense.api.decision.DecisionRequest;
import com.airsense.api.decision.DecisionSummary;
import com.airsense.api.decision.PriorityAction;
import com.airsense.api.decision.RiskAssessment;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.geospatial.GeoSpatialIntelligenceResult;
import com.airsense.api.geospatial.GeoSpatialIntelligenceService;
import com.airsense.api.geospatial.GeoSpatialLayer;
import com.airsense.api.geospatial.GeoSpatialLayerType;
import com.airsense.api.geospatial.GeoSpatialRequest;
import com.airsense.api.entities.CityMetricsSnapshot;
import com.airsense.api.repositories.CityMetricsSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemporalIntelligenceService {
    private final DecisionIntelligenceService decisionService;
    private final GeoSpatialIntelligenceService geoSpatialService;
    private final CityMetricsSnapshotRepository snapshotRepository;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${airsense.temporal.cache-ttl-seconds:300}")
    private long cacheTtlSeconds;

    public TimelineResult timeline(TemporalRequest request) {
        TemporalRequest safeRequest = (request != null ? request : TemporalRequest.builder().build()).normalized();
        CacheEntry cached = cache.get(safeRequest.cacheKey());
        if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
            log.info("TemporalIntelligence Using Cached cityId={} frames={}", safeRequest.getCityId(), cached.result().getFrames().size());
            return withCacheStatus(cached.result(), "HIT");
        }

        log.info("TemporalIntelligence Fetching cityId={}", safeRequest.getCityId());
        DecisionIntelligenceResult decision = decisionService.decide(DecisionRequest.builder()
                .cityId(safeRequest.getCityId())
                .placeId(safeRequest.getPlaceId())
                .cityName(safeRequest.getCityName())
                .state(safeRequest.getState())
                .country(safeRequest.getCountry())
                .wardId(safeRequest.getWardId())
                .latitude(safeRequest.getLatitude())
                .longitude(safeRequest.getLongitude())
                .build());
        persistDailySnapshot(decision);
        GeoSpatialIntelligenceResult geospatial = geoSpatialService.generate(GeoSpatialRequest.builder()
                .cityId(safeRequest.getCityId())
                .placeId(safeRequest.getPlaceId())
                .cityName(safeRequest.getCityName())
                .state(safeRequest.getState())
                .country(safeRequest.getCountry())
                .wardId(safeRequest.getWardId())
                .latitude(safeRequest.getLatitude())
                .longitude(safeRequest.getLongitude())
                .build());

        TimelineResult result = assemble(decision, geospatial, safeRequest, "MISS");
        cache.put(safeRequest.cacheKey(), new CacheEntry(result, Instant.now()));
        log.info("TemporalIntelligence Success cityId={} frames={} cacheTtlSeconds={}",
                result.getCityId(), result.getFrames().size(), cacheTtlSeconds);
        return result;
    }

    public TimelineResult assemble(DecisionIntelligenceResult decision, GeoSpatialIntelligenceResult geospatial,
                                   TemporalRequest request, String cacheStatus) {
        TemporalRequest safeRequest = (request != null ? request : TemporalRequest.builder().build()).normalized();
        DecisionIntelligenceResult safeDecision = decision != null ? decision : DecisionIntelligenceResult.builder()
                .cityId(safeRequest.getCityId())
                .city(safeRequest.getCityId())
                .generatedAt(Instant.now())
                .currentAQI(null)
                .overallConfidence(0.15)
                .build();
        GeoSpatialIntelligenceResult safeGeospatial = geospatial != null ? geospatial : GeoSpatialIntelligenceResult.builder()
                .cityId(safeRequest.getCityId())
                .city(safeRequest.getCityId())
                .generatedAt(Instant.now())
                .geometrySource("unavailable")
                .degradedMode(true)
                .build();

        Instant base = safeDecision.getGeneratedAt() != null ? safeDecision.getGeneratedAt() : Instant.now();
        List<TimelineFrame> frames = new ArrayList<>();
        frames.add(frame("historical", "Historical", "HISTORICAL", -24, historicalTimestamp(safeDecision, base), historicalAqi(safeDecision), null, safeDecision, safeGeospatial));
        frames.add(frame("current", "Current", "CURRENT", 0, base, currentAqi(safeDecision), null, safeDecision, safeGeospatial));
        if (hasAvailableForecast(safeDecision)) {
            frames.add(frame("plus-24h", "+24h", "FORECAST", 24, base.plusSeconds(24 * 3600L), forecastAqi(safeDecision, "24h"), "24h", safeDecision, safeGeospatial));
            frames.add(frame("plus-48h", "+48h", "FORECAST", 48, base.plusSeconds(48 * 3600L), forecastAqi(safeDecision, "48h"), "48h", safeDecision, safeGeospatial));
            frames.add(frame("plus-72h", "+72h", "FORECAST", 72, base.plusSeconds(72 * 3600L), forecastAqi(safeDecision, "72h"), "72h", safeDecision, safeGeospatial));
        }

        List<TimelineEvent> events = frames.stream()
                .map(frame -> TimelineEvent.builder()
                        .eventId("event-" + frame.getFrameId())
                        .eventType(frame.getFrameType())
                        .title(frame.getLabel() + " AQI " + frame.getAqi())
                        .description("Dominant source: " + value(frame.getDominantSource(), "UNKNOWN") + "; risk: " + riskLevel(frame.getAqi()))
                        .timestamp(frame.getTimestamp())
                        .severity(riskLevel(frame.getAqi()))
                        .confidence(frame.getDecision() != null ? frame.getDecision().getOverallConfidence() : 0.15)
                        .build())
                .toList();

        return TimelineResult.builder()
                .city(value(safeDecision.getCity(), safeGeospatial.getCity()))
                .cityId(value(safeDecision.getCityId(), safeRequest.getCityId()))
                .generatedAt(Instant.now())
                .cacheStatus(value(cacheStatus, "MISS"))
                .cacheTtlSeconds(cacheTtlSeconds)
                .frames(frames)
                .events(events)
                .metadata(Map.of(
                        "frameCount", frames.size(),
                        "source", "decision_and_geospatial_engines",
                        "cacheKey", safeRequest.cacheKey()
                ))
                .build();
    }

    private TimelineResult withCacheStatus(TimelineResult source, String cacheStatus) {
        return TimelineResult.builder()
                .city(source.getCity())
                .cityId(source.getCityId())
                .generatedAt(source.getGeneratedAt())
                .cacheStatus(cacheStatus)
                .cacheTtlSeconds(source.getCacheTtlSeconds())
                .frames(source.getFrames())
                .events(source.getEvents())
                .metadata(source.getMetadata())
                .build();
    }

    private TimelineFrame frame(String frameId, String label, String frameType, int offsetHours, Instant timestamp,
                                int frameAqi, String horizonKey, DecisionIntelligenceResult decision,
                                GeoSpatialIntelligenceResult geospatial) {
        ForecastPoint activePoint = forecastPoint(decision, horizonKey);
        int aqi = frameAqi > 0 ? frameAqi : currentAqi(decision);
        RiskAssessment risk = frameRisk(decision.getRiskAssessment(), aqi);
        List<TimelineLayer> layers = frameLayers(geospatial, frameId, horizonKey, aqi);
        GeoSpatialIntelligenceResult frameGeoSpatial = frameGeospatial(geospatial, layers, frameId);
        DecisionIntelligenceResult frameDecision = frameDecision(decision, aqi, risk, frameType, label);
        List<PriorityAction> recommendations = decision.getPriorityActions() != null ? decision.getPriorityActions() : List.of();

        return TimelineFrame.builder()
                .frameId(frameId)
                .label(label)
                .frameType(frameType)
                .offsetHours(offsetHours)
                .timestamp(timestamp)
                .aqi(aqi)
                .activeForecastPoint(activePoint)
                .forecast(frameForecast(decision.getForecast(), horizonKey, frameType))
                .attribution(decision.getAttribution())
                .dominantSource(dominantSource(decision.getAttribution(), activePoint))
                .recommendations(recommendations)
                .risk(risk)
                .wind(windFromLayers(layers))
                .hotspots(hotspotsFromLayers(layers))
                .geoJsonLayers(layers)
                .geospatial(frameGeoSpatial)
                .decision(frameDecision)
                .events(List.of(TimelineEvent.builder()
                        .eventId(frameId + "-risk")
                        .eventType(frameType)
                        .title(label + " risk: " + riskLevel(aqi))
                        .description("AQI " + aqi + " with dominant source " + dominantSource(decision.getAttribution(), activePoint))
                        .timestamp(timestamp)
                        .severity(riskLevel(aqi))
                        .confidence(frameDecision.getOverallConfidence())
                        .build()))
                .metadata(Map.of(
                        "horizonKey", horizonKey != null ? horizonKey : "current",
                        "frameSource", "decision_geospatial_snapshot",
                        "historicalProjection", "HISTORICAL".equals(frameType)
                ))
                .build();
    }

    private DecisionIntelligenceResult frameDecision(DecisionIntelligenceResult source, int aqi, RiskAssessment risk,
                                                     String frameType, String label) {
        DecisionSummary original = source.getSummary();
        DecisionSummary summary = DecisionSummary.builder()
                .whatIsHappening(label + " frame AQI is " + aqi + " with " + riskLevel(aqi) + " risk.")
                .whyIsItHappening(original != null ? original.getWhyIsItHappening() : "Source attribution is unavailable for this frame.")
                .whatWillHappenNext(original != null ? original.getWhatWillHappenNext() : "Forecast narrative unavailable.")
                .whatShouldOfficialsDoNow(original != null ? original.getWhatShouldOfficialsDoNow() : "Continue monitoring.")
                .whatShouldCitizensDoNow(original != null ? original.getWhatShouldCitizensDoNow() : "Follow AQI guidance.")
                .build();
        return DecisionIntelligenceResult.builder()
                .city(source.getCity())
                .cityId(source.getCityId())
                .generatedAt(source.getGeneratedAt())
                .summary(summary)
                .riskAssessment(risk)
                .currentAQI(aqi)
                .forecast(source.getForecast())
                .attribution(source.getAttribution())
                .enforcement(source.getEnforcement())
                .advisories(source.getAdvisories())
                .priorityActions(source.getPriorityActions())
                .evidenceBundle(source.getEvidenceBundle())
                .engineStatus(source.getEngineStatus())
                .overallConfidence(source.getOverallConfidence())
                .geospatialSummary(source.getGeospatialSummary())
                .geospatialEndpoint(source.getGeospatialEndpoint())
                .explainabilitySummary(source.getExplainabilitySummary())
                .explainabilityEndpoint(source.getExplainabilityEndpoint())
                .environmentalSignals(source.getEnvironmentalSignals())
                .build();
    }

    private ForecastResult frameForecast(ForecastResult source, String horizonKey, String frameType) {
        if (source == null) return ForecastResult.builder().fallbackUsed(true).overallConfidence(0.15).build();
        Map<String, ForecastPoint> forecast = source.getForecast() != null ? new LinkedHashMap<>(source.getForecast()) : new LinkedHashMap<>();
        return ForecastResult.builder()
                .city(source.getCity())
                .cityId(source.getCityId())
                .wardId(source.getWardId())
                .generatedAt(source.getGeneratedAt())
                .snapshotId(source.getSnapshotId())
                .locationHash(source.getLocationHash())
                .forecast(forecast)
                .overallConfidence(source.getOverallConfidence())
                .overallTrend("HISTORICAL".equals(frameType) ? "historical" : source.getOverallTrend())
                .fallbackUsed(source.isFallbackUsed())
                .modelVersion(source.getModelVersion())
                .mode(source.getMode())
                .formulaVersion(source.getFormulaVersion())
                .providerStatus(source.getProviderStatus())
                .build();
    }

    private GeoSpatialIntelligenceResult frameGeospatial(GeoSpatialIntelligenceResult source, List<TimelineLayer> layers, String frameId) {
        List<GeoSpatialLayer> geoLayers = layers.stream()
                .map(layer -> GeoSpatialLayer.builder()
                        .layerId(layer.getLayerId())
                        .layerType(layer.getLayerType())
                        .displayName(layer.getDisplayName())
                        .description(layer.getDescription())
                        .confidence(layer.getConfidence())
                        .generatedAt(layer.getGeneratedAt())
                        .geoJson(layer.getGeoJson())
                        .legend(layer.getLegend())
                        .metadata(layer.getMetadata())
                        .evidence(layer.getEvidence())
                        .build())
                .toList();
        return GeoSpatialIntelligenceResult.builder()
                .city(source.getCity())
                .cityId(source.getCityId())
                .generatedAt(source.getGeneratedAt())
                .geometrySource(source.getGeometrySource())
                .degradedMode(source.isDegradedMode())
                .layers(geoLayers)
                .layerStatus(source.getLayerStatus())
                .metadata(Map.of(
                        "center", source.getMetadata() != null ? source.getMetadata().getOrDefault("center", Map.of()) : Map.of(),
                        "timelineFrameId", frameId,
                        "source", "temporal_frame"
                ))
                .build();
    }

    private List<TimelineLayer> frameLayers(GeoSpatialIntelligenceResult geospatial, String frameId, String horizonKey, int frameAqi) {
        List<GeoSpatialLayer> sourceLayers = geospatial != null && geospatial.getLayers() != null ? geospatial.getLayers() : List.of();
        return sourceLayers.stream()
                .filter(layer -> includeLayer(layer, horizonKey))
                .map(layer -> timelineLayer(layer, frameId, frameAqi))
                .sorted(Comparator.comparing(layer -> layer.getLayerType() != null ? layer.getLayerType().name() : layer.getLayerId()))
                .toList();
    }

    private boolean includeLayer(GeoSpatialLayer layer, String horizonKey) {
        if (horizonKey == null || layer.getLayerType() == null || !layer.getLayerType().name().startsWith("FORECAST_GRID")) {
            return true;
        }
        return switch (horizonKey) {
            case "24h" -> layer.getLayerType() == GeoSpatialLayerType.FORECAST_GRID_24H;
            case "48h" -> layer.getLayerType() == GeoSpatialLayerType.FORECAST_GRID_48H;
            case "72h" -> layer.getLayerType() == GeoSpatialLayerType.FORECAST_GRID_72H;
            default -> true;
        };
    }

    private TimelineLayer timelineLayer(GeoSpatialLayer layer, String frameId, int frameAqi) {
        Map<String, Object> metadata = new LinkedHashMap<>(layer.getMetadata() != null ? layer.getMetadata() : Map.of());
        metadata.put("timelineFrameId", frameId);
        Map<String, Object> geoJson = rewriteAqi(layer.getGeoJson(), frameAqi, frameId);
        return TimelineLayer.builder()
                .layerId(layer.getLayerId())
                .layerType(layer.getLayerType())
                .displayName(layer.getDisplayName())
                .description(layer.getDescription())
                .confidence(layer.getConfidence())
                .generatedAt(layer.getGeneratedAt())
                .geoJson(geoJson)
                .legend(layer.getLegend())
                .metadata(metadata)
                .evidence(layer.getEvidence())
                .build();
    }

    private Map<String, Object> rewriteAqi(Map<String, Object> geoJson, int frameAqi, String frameId) {
        if (geoJson == null || geoJson.isEmpty()) return Map.of("type", "FeatureCollection", "features", List.of());
        List<Map<String, Object>> features = new ArrayList<>();
        Object rawFeatures = geoJson.get("features");
        if (rawFeatures instanceof List<?> list) {
            for (Object rawFeature : list) {
                if (rawFeature instanceof Map<?, ?> feature) {
                    Map<String, Object> copied = new LinkedHashMap<>();
                    feature.forEach((key, value) -> copied.put(String.valueOf(key), value));
                    Map<String, Object> props = new LinkedHashMap<>();
                    Object rawProps = feature.get("properties");
                    if (rawProps instanceof Map<?, ?> propMap) {
                        propMap.forEach((key, value) -> props.put(String.valueOf(key), value));
                    }
                    props.put("aqi", frameAqi);
                    props.put("predictedAqi", frameAqi);
                    props.put("riskLevel", riskLevel(frameAqi));
                    props.put("timelineFrameId", frameId);
                    copied.put("properties", props);
                    features.add(copied);
                }
            }
        }
        return Map.of("type", value(String.valueOf(geoJson.get("type")), "FeatureCollection"), "features", features);
    }

    private RiskAssessment frameRisk(RiskAssessment source, int aqi) {
        return RiskAssessment.builder()
                .currentRisk(riskLevel(aqi))
                .forecastRisk(riskLevel(aqi))
                .dominantSourceRisk(source != null ? source.getDominantSourceRisk() : "UNKNOWN")
                .populationExposureRisk(source != null ? source.getPopulationExposureRisk() : "UNKNOWN")
                .sensitiveZoneRisk(source != null ? source.getSensitiveZoneRisk() : "UNKNOWN")
                .overallRiskLevel(riskLevel(aqi))
                .build();
    }

    private int currentAqi(DecisionIntelligenceResult decision) {
        return decision != null && decision.getCurrentAQI() != null && decision.getCurrentAQI() > 0 ? decision.getCurrentAQI() : 0;
    }

    private int historicalAqi(DecisionIntelligenceResult decision) {
        if (snapshotRepository != null && decision != null && decision.getCityId() != null) {
            List<CityMetricsSnapshot> snapshots = snapshotRepository.findByCityIdOrderByTimestampDesc(decision.getCityId());
            if (!snapshots.isEmpty()) {
                return snapshots.get(0).getCurrentAqi();
            }
        }
        int current = currentAqi(decision);
        ForecastPoint point = forecastPoint(decision, "24h");
        int trendDelta = point != null && point.getPredictedAqi() != null ? point.getPredictedAqi() - current : 0;
        int historical = current - Math.max(-25, Math.min(25, trendDelta / 2));
        return Math.max(0, historical);
    }

    private Instant historicalTimestamp(DecisionIntelligenceResult decision, Instant fallbackBase) {
        if (snapshotRepository != null && decision != null && decision.getCityId() != null) {
            List<CityMetricsSnapshot> snapshots = snapshotRepository.findByCityIdOrderByTimestampDesc(decision.getCityId());
            if (!snapshots.isEmpty() && snapshots.get(0).getTimestamp() != null) {
                return snapshots.get(0).getTimestamp();
            }
        }
        return fallbackBase.minusSeconds(24 * 3600L);
    }

    private void persistDailySnapshot(DecisionIntelligenceResult decision) {
        if (snapshotRepository == null || decision == null || decision.getCityId() == null || decision.getCityId().isBlank()) return;
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            boolean existsToday = snapshotRepository.findByCityIdOrderByTimestampDesc(decision.getCityId()).stream()
                    .map(CityMetricsSnapshot::getTimestamp)
                    .filter(timestamp -> timestamp != null)
                    .map(timestamp -> LocalDate.ofInstant(timestamp, ZoneOffset.UTC))
                    .anyMatch(today::equals);
            if (existsToday) return;

            CityMetricsSnapshot snapshot = new CityMetricsSnapshot();
            snapshot.setCityId(decision.getCityId());
            snapshot.setTimestamp(Instant.now());
            snapshot.setCurrentAqi(currentAqi(decision));
            snapshot.setForecastPeakAqi(forecastPeak(decision));
            snapshot.setDominantSource(decision.getAttribution() != null && decision.getAttribution().getDominantSource() != null
                    ? decision.getAttribution().getDominantSource().name()
                    : "UNKNOWN");
            snapshot.setOpenEnforcementCount(decision.getEnforcement() != null && decision.getEnforcement().getRecommendations() != null
                    ? decision.getEnforcement().getRecommendations().size()
                    : 0);
            snapshot.setAdvisoryCount(decision.getAdvisories() != null && decision.getAdvisories().getAdvisories() != null
                    ? decision.getAdvisories().getAdvisories().size()
                    : 0);
            snapshotRepository.save(snapshot);
            log.info("TemporalIntelligence Snapshot Persisted cityId={} currentAqi={}", decision.getCityId(), currentAqi(decision));
        } catch (Exception e) {
            log.warn("TemporalIntelligence Snapshot Persist Failed cityId={} reason={}", decision.getCityId(), e.getMessage());
        }
    }

    private int forecastPeak(DecisionIntelligenceResult decision) {
        return decision.getForecast() != null && decision.getForecast().getForecast() != null
                ? decision.getForecast().getForecast().values().stream()
                        .map(ForecastPoint::getPredictedAqi)
                        .filter(value -> value != null && value > 0)
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(currentAqi(decision))
                : currentAqi(decision);
    }

    private int forecastAqi(DecisionIntelligenceResult decision, String key) {
        ForecastPoint point = forecastPoint(decision, key);
        return point != null && point.getPredictedAqi() != null && point.getPredictedAqi() > 0 ? point.getPredictedAqi() : 0;
    }

    private ForecastPoint forecastPoint(DecisionIntelligenceResult decision, String key) {
        if (decision == null || key == null || decision.getForecast() == null || decision.getForecast().getForecast() == null) {
            return null;
        }
        return decision.getForecast().getForecast().get(key);
    }

    private boolean hasAvailableForecast(DecisionIntelligenceResult decision) {
        if (decision == null || decision.getForecast() == null || decision.getForecast().getForecast() == null) {
            return false;
        }
        return decision.getForecast().getForecast().values().stream()
                .anyMatch(point -> point.getPredictedAqi() != null && point.getPredictedAqi() > 0);
    }

    private String dominantSource(AttributionResult attribution, ForecastPoint point) {
        if (point != null && point.getExpectedDominantSource() != null) return point.getExpectedDominantSource().name();
        if (attribution != null && attribution.getDominantSource() != null) return attribution.getDominantSource().name();
        return "UNKNOWN";
    }

    private Map<String, Object> windFromLayers(List<TimelineLayer> layers) {
        return layers.stream()
                .filter(layer -> layer.getLayerType() == GeoSpatialLayerType.WIND_VECTOR_LAYER)
                .findFirst()
                .map(layer -> Map.<String, Object>of(
                        "status", layer.getMetadata() != null ? layer.getMetadata().getOrDefault("status", "UNKNOWN") : "UNKNOWN",
                        "confidence", layer.getConfidence(),
                        "evidence", layer.getEvidence() != null ? layer.getEvidence() : List.of()
                ))
                .orElse(Map.of("status", "UNAVAILABLE", "confidence", 0.0));
    }

    private List<Map<String, Object>> hotspotsFromLayers(List<TimelineLayer> layers) {
        return layers.stream()
                .filter(layer -> layer.getLayerType() == GeoSpatialLayerType.AQI_HOTSPOTS)
                .findFirst()
                .map(layer -> {
                    Object features = layer.getGeoJson() != null ? layer.getGeoJson().get("features") : List.of();
                    if (features instanceof List<?> list) {
                        List<Map<String, Object>> mapped = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> map) {
                                Map<String, Object> feature = new HashMap<>();
                                map.forEach((key, value) -> feature.put(String.valueOf(key), value));
                                mapped.add(feature);
                            }
                        }
                        return mapped;
                    }
                    return List.<Map<String, Object>>of();
                })
                .orElse(List.of());
    }

    private String riskLevel(int aqi) {
        if (aqi <= 50) return "GOOD";
        if (aqi <= 100) return "NORMAL";
        if (aqi <= 200) return "ELEVATED";
        if (aqi <= 300) return "HIGH";
        if (aqi <= 400) return "SEVERE";
        return "EMERGENCY";
    }

    private String value(String value, String fallback) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value) ? value : fallback;
    }

    private record CacheEntry(TimelineResult result, Instant cachedAt) {
        boolean isExpired(long ttlSeconds) {
            return cachedAt.plusSeconds(ttlSeconds).isBefore(Instant.now());
        }
    }
}
