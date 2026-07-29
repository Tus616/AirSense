package com.airsense.api.geospatial;

import com.airsense.api.advisory.HealthAdvisoryResult;
import com.airsense.api.advisory.HealthAdvisoryService;
import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionAttributionService;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.data.GeoJsonImportService;
import com.airsense.api.data.OpenStreetMapDataService;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.DecisionIntelligenceService;
import com.airsense.api.decision.DecisionRequest;
import com.airsense.api.enforcement.EnforcementIntelligenceService;
import com.airsense.api.enforcement.EnforcementRecommendation;
import com.airsense.api.enforcement.EnforcementRequest;
import com.airsense.api.enforcement.EnforcementResult;
import com.airsense.api.forecast.ForecastOrchestrator;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastRequest;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeoSpatialIntelligenceService {
    private static final List<GeoSpatialLayerType> REQUIRED_LAYERS = List.of(
            GeoSpatialLayerType.AQI_HOTSPOTS,
            GeoSpatialLayerType.FORECAST_GRID_24H,
            GeoSpatialLayerType.FORECAST_GRID_48H,
            GeoSpatialLayerType.FORECAST_GRID_72H,
            GeoSpatialLayerType.POLLUTION_SOURCE_ZONES,
            GeoSpatialLayerType.TRAFFIC_CORRIDORS,
            GeoSpatialLayerType.CONSTRUCTION_SITES,
            GeoSpatialLayerType.INDUSTRIAL_ZONES,
            GeoSpatialLayerType.SENSITIVE_ZONES,
            GeoSpatialLayerType.WIND_VECTOR_LAYER,
            GeoSpatialLayerType.GREEN_COVER_LAYER,
            GeoSpatialLayerType.SATELLITE_EVIDENCE_LAYER
    );

    private final DataFusionService dataFusionService;
    private final PollutionAttributionService attributionService;
    @Qualifier("hyperlocalForecastOrchestrator")
    private final ForecastOrchestrator forecastOrchestrator;
    @Qualifier("phase24EnforcementIntelligenceService")
    private final EnforcementIntelligenceService enforcementService;
    private final HealthAdvisoryService advisoryService;
    private final DecisionIntelligenceService decisionService;
    private final GeoSpatialProperties properties;
    private final OpenStreetMapDataService openStreetMapDataService;
    private final GeoJsonImportService geoJsonImportService;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GeoSpatialIntelligenceResult generate(GeoSpatialRequest request) {
        GeoSpatialRequest safeRequest = (request != null ? request : GeoSpatialRequest.builder().build()).normalized();
        CacheEntry cached = cache.get(safeRequest.cacheKey());
        if (cached != null && !cached.isExpired(properties.getCacheTtlSeconds())) {
            log.info("GeospatialIntelligence Using Cached cityId={}", safeRequest.getCityId());
            return cached.result();
        }

        log.info("GeospatialIntelligence Fetching cityId={}", safeRequest.getCityId());
        GeoSpatialIntelligenceResult result;
        try {
            CityEnvironmentalContext context = dataFusionService.buildContext(FusionRequest.builder()
                    .cityId(safeRequest.getCityId())
                    .placeId(safeRequest.getPlaceId())
                    .cityName(safeRequest.getCityName())
                    .state(safeRequest.getState())
                    .country(safeRequest.getCountry())
                    .latitude(safeRequest.getLatitude())
                    .longitude(safeRequest.getLongitude())
                    .build());
            AttributionResult attribution = attributionService.attribute(context, AttributionRequest.builder()
                    .cityId(safeRequest.getCityId())
                    .placeId(safeRequest.getPlaceId())
                    .cityName(safeRequest.getCityName())
                    .state(safeRequest.getState())
                    .country(safeRequest.getCountry())
                    .wardId(safeRequest.getWardId())
                    .latitude(safeRequest.getLatitude())
                    .longitude(safeRequest.getLongitude())
                    .build());
            ForecastResult forecast = forecastOrchestrator.forecast(context, ForecastRequest.builder()
                    .cityId(safeRequest.getCityId())
                    .placeId(safeRequest.getPlaceId())
                    .cityName(safeRequest.getCityName())
                    .state(safeRequest.getState())
                    .country(safeRequest.getCountry())
                    .wardId(safeRequest.getWardId())
                    .latitude(safeRequest.getLatitude())
                    .longitude(safeRequest.getLongitude())
                    .build(), attribution);
            EnforcementResult enforcement = enforcementService.recommend(context, attribution, forecast, EnforcementRequest.builder()
                    .cityId(safeRequest.getCityId())
                    .placeId(safeRequest.getPlaceId())
                    .cityName(safeRequest.getCityName())
                    .state(safeRequest.getState())
                    .country(safeRequest.getCountry())
                    .wardId(safeRequest.getWardId())
                    .latitude(safeRequest.getLatitude())
                    .longitude(safeRequest.getLongitude())
                    .build());
            HealthAdvisoryResult advisory = advisoryService.generate(context, attribution, forecast, enforcement,
                    com.airsense.api.advisory.HealthAdvisoryRequest.builder()
                            .cityId(safeRequest.getCityId())
                            .placeId(safeRequest.getPlaceId())
                            .cityName(safeRequest.getCityName())
                            .state(safeRequest.getState())
                            .country(safeRequest.getCountry())
                            .wardId(safeRequest.getWardId())
                            .latitude(safeRequest.getLatitude())
                            .longitude(safeRequest.getLongitude())
                            .build());
            DecisionIntelligenceResult decision = decisionService.assemble(context, attribution, forecast, enforcement, advisory,
                    DecisionRequest.builder()
                            .cityId(safeRequest.getCityId())
                            .placeId(safeRequest.getPlaceId())
                            .cityName(safeRequest.getCityName())
                            .state(safeRequest.getState())
                            .country(safeRequest.getCountry())
                            .wardId(safeRequest.getWardId())
                            .latitude(safeRequest.getLatitude())
                            .longitude(safeRequest.getLongitude())
                            .build(),
                    Map.of());
            result = assemble(context, attribution, forecast, enforcement, advisory, decision);
        } catch (Exception e) {
            log.warn("GeospatialIntelligence Fallback cityId={} reason={}", safeRequest.getCityId(), e.getMessage());
            CityEnvironmentalContext fallbackContext = CityEnvironmentalContext.empty(safeRequest.getCityId());
            if (safeRequest.getLatitude() != null && safeRequest.getLongitude() != null) {
                fallbackContext.setCoordinates(Map.of("latitude", safeRequest.getLatitude(), "longitude", safeRequest.getLongitude()));
            }
            result = assemble(fallbackContext,
                    AttributionResult.builder().cityId(safeRequest.getCityId()).overallConfidence(0.15).explanation("Geospatial fallback: " + e.getMessage()).build(),
                    ForecastResult.builder().cityId(safeRequest.getCityId()).overallConfidence(0.15).fallbackUsed(true).forecast(Map.of()).build(),
                    EnforcementResult.builder().cityId(safeRequest.getCityId()).currentAqi(null).forecastPeakAqi(null).build(),
                    HealthAdvisoryResult.builder().cityId(safeRequest.getCityId()).build(),
                    DecisionIntelligenceResult.builder().cityId(safeRequest.getCityId()).city(safeRequest.getCityId()).currentAQI(null).overallConfidence(0.15).build());
            result.setDegradedMode(true);
            result.getMetadata().put("fallbackReason", e.getMessage());
        }
        cache.put(safeRequest.cacheKey(), new CacheEntry(result, Instant.now()));
        log.info("GeospatialIntelligence Success cityId={} layers={} geometrySource={}",
                result.getCityId(), result.getLayers().size(), result.getGeometrySource());
        return result;
    }

    public GeoSpatialIntelligenceResult assemble(CityEnvironmentalContext context,
                                                 AttributionResult attribution,
                                                 ForecastResult forecast,
                                                 EnforcementResult enforcement,
                                                 HealthAdvisoryResult advisory,
                                                 DecisionIntelligenceResult decision) {
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(decision != null ? decision.getCityId() : "UNKNOWN_PLACE");
        AttributionResult safeAttribution = attribution != null ? attribution : AttributionResult.builder().build();
        ForecastResult safeForecast = forecast != null ? forecast : ForecastResult.builder().build();
        EnforcementResult safeEnforcement = enforcement != null ? enforcement : EnforcementResult.builder().build();
        DecisionIntelligenceResult safeDecision = decision != null ? decision : DecisionIntelligenceResult.builder()
                .city(safeContext.getCity())
                .cityId(safeContext.getCityId())
                .currentAQI(currentAqi(safeContext, safeEnforcement))
                .forecast(safeForecast)
                .attribution(safeAttribution)
                .enforcement(safeEnforcement)
                .advisories(advisory)
                .overallConfidence(0.25)
                .build();

        Point center = center(safeContext);
        Map<String, Object> osmData = openStreetMapData(center);
        Instant generatedAt = Instant.now();
        List<GeoSpatialLayer> layers = new ArrayList<>();
        layers.add(aqiHotspots(safeContext, safeAttribution, safeEnforcement, safeDecision, center, osmData, generatedAt));
        layers.add(forecastLayer(GeoSpatialLayerType.FORECAST_GRID_24H, "24h Forecast Risk Grid", safeForecast, "24h", safeDecision, center, generatedAt));
        layers.add(forecastLayer(GeoSpatialLayerType.FORECAST_GRID_48H, "48h Forecast Risk Grid", safeForecast, "48h", safeDecision, center, generatedAt));
        layers.add(forecastLayer(GeoSpatialLayerType.FORECAST_GRID_72H, "72h Forecast Risk Grid", safeForecast, "72h", safeDecision, center, generatedAt));
        layers.add(sourceZones(safeAttribution, safeDecision, center, generatedAt));
        layers.add(trafficCorridors(safeContext, safeDecision, center, osmData, generatedAt));
        layers.add(constructionSites(safeContext, safeDecision, center, generatedAt));
        layers.add(industrialZones(safeContext, safeDecision, center, generatedAt));
        layers.add(sensitiveZones(safeContext, safeDecision, center, osmData, generatedAt));
        layers.add(windVector(safeContext, safeDecision, center, generatedAt));
        layers.add(greenCover(safeContext, safeDecision, center, osmData, generatedAt));
        layers.add(satelliteEvidence(safeContext, safeDecision, center, generatedAt));

        Map<String, String> status = new LinkedHashMap<>();
        layers.forEach(layer -> status.put(layer.getLayerId(), String.valueOf(layer.getMetadata().getOrDefault("status", "SUCCESS"))));
        String geometrySource = layers.stream()
                .map(layer -> String.valueOf(layer.getMetadata().getOrDefault("geometrySource", "unavailable")))
                .filter(source -> !"unavailable".equals(source))
                .distinct()
                .reduce((left, right) -> left.equals(right) ? left : "mixed_real_available_layers")
                .orElse("unavailable");

        return GeoSpatialIntelligenceResult.builder()
                .city(value(safeDecision.getCity(), value(safeContext.getCity(), safeContext.getCityId())))
                .cityId(value(safeDecision.getCityId(), safeContext.getCityId()))
                .generatedAt(generatedAt)
                .geometrySource(geometrySource)
                .degradedMode(layers.stream().anyMatch(layer -> layer.getConfidence() < 0.4))
                .layers(layers)
                .layerStatus(status)
                .metadata(Map.of(
                        "requiredLayerCount", REQUIRED_LAYERS.size(),
                        "returnedLayerCount", layers.size(),
                        "center", Map.of("latitude", center.lat(), "longitude", center.lng())
                ))
                .build();
    }

    public GeoSpatialSummary summarize(GeoSpatialIntelligenceResult result) {
        if (result == null) {
            return GeoSpatialSummary.builder().layerCount(0).geometrySource("unavailable").confidence(0.0).degradedMode(true).build();
        }
        double confidence = result.getLayers() != null && !result.getLayers().isEmpty()
                ? result.getLayers().stream().mapToDouble(GeoSpatialLayer::getConfidence).average().orElse(0.0)
                : 0.0;
        return GeoSpatialSummary.builder()
                .layerCount(result.getLayers() != null ? result.getLayers().size() : 0)
                .geometrySource(result.getGeometrySource())
                .confidence(round(confidence))
                .degradedMode(result.isDegradedMode())
                .build();
    }

    private GeoSpatialLayer aqiHotspots(CityEnvironmentalContext context, AttributionResult attribution,
                                        EnforcementResult enforcement, DecisionIntelligenceResult decision,
                                        Point center, Map<String, Object> osmData, Instant generatedAt) {
        int aqi = (int) Math.round(firstPositive(currentAqi(context, enforcement), currentAqi(decision), forecastPeak(decision)));
        List<Map<String, Object>> boundaryFeatures = mergeFeatures(
                importedFeatures("adminBoundaries"),
                osmFeatures(osmData, "adminBoundaries")
        );
        List<Map<String, Object>> features;
        Map<String, Object> metadata;
        double confidence = providerConfidence(context, "aqi", decision.getOverallConfidence());
        if (!boundaryFeatures.isEmpty()) {
            features = enrichFeatures(boundaryFeatures, baseProperties(riskLevel(aqi), aqi, 0, dominant(attribution, decision), 0,
                    confidence, "Investigate AQI hotspot", "", "AQI risk projected onto administrative boundary", List.of("aqi", "adminBoundaries")));
            metadata = metadata(sourceName(boundaryFeatures), "SUCCESS", "DERIVED_FROM_REAL_DATA", "AQI hotspot layer uses imported or OSM administrative boundary geometry.");
        } else if (validCenter(center) && aqi > 0) {
            Map<String, Object> props = baseProperties(riskLevel(aqi), aqi, 0, dominant(attribution, decision), 0,
                    Math.min(confidence, 0.55), "Investigate selected-location AQI risk zone", "City command center",
                    "Deterministic circular zone centered on the selected coordinates because exact hotspot geometry was not available.",
                    List.of("aqi", "selectedLocation"));
            props.put("radiusMeters", hotspotRadiusMeters(aqi));
            props.put("featureKind", "CIRCLE");
            props.put("geometrySource", "selected_location_circle");
            props.put("dataOrigin", "DERIVED_FROM_REAL_DATA");
            props.put("limitations", List.of("Circle is a decision-support risk area, not an observed administrative boundary or exact plume."));
            features = List.of(feature("selected-location-aqi-hotspot", point(center), props));
            confidence = Math.min(confidence, 0.55);
            metadata = metadata("selected_location_circle", "DERIVED", "DERIVED_FROM_REAL_DATA",
                    "AQI hotspot circle is derived from real selected coordinates and current/forecast AQI because exact hotspot geometry is unavailable.");
        } else {
            features = List.of();
            metadata = metadata("unavailable", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE", "No real hotspot/admin geometry available; live mode does not generate synthetic AQI zones.");
        }
        return layer(GeoSpatialLayerType.AQI_HOTSPOTS, "AQI Hotspots",
                "Current AQI hotspot zones generated from fused city AQI and risk level.",
                confidence, generatedAt, features,
                legend("GOOD", "#55a84f", "LOW", "#f29c33", "HIGH", "#e93f33"),
                metadata,
                List.of("Current AQI: " + aqi, "Dominant source: " + dominant(attribution, decision)));
    }

    private GeoSpatialLayer forecastLayer(GeoSpatialLayerType type, String displayName, ForecastResult forecast,
                                          String horizonKey, DecisionIntelligenceResult decision, Point center, Instant generatedAt) {
        ForecastPoint point = forecast.getForecast() != null ? forecast.getForecast().get(horizonKey) : null;
        Integer predicted = point != null ? point.getPredictedAqi() : null;
        double confidence = point != null ? point.getConfidence() : 0.25;
        List<Map<String, Object>> features;
        Map<String, Object> metadata;
        if (predicted != null && predicted > 0 && validCenter(center)) {
            Map<String, Object> props = baseProperties(riskLevel(predicted), currentAqi(decision), predicted,
                    dominant(point, decision), 0, confidence, "Monitor " + horizonKey + " forecast risk zone",
                    "City command center", explanation(point), datasets(point));
            props.put("radiusMeters", forecastRadiusMeters(type, predicted));
            props.put("featureKind", "CIRCLE");
            props.put("horizon", horizonKey);
            props.put("geometrySource", "selected_location_circle");
            props.put("dataOrigin", forecastOrigin(forecast, point));
            props.put("limitations", List.of("Forecast circle is centered on selected coordinates; it is not a trained spatial plume grid."));
            features = List.of(feature("forecast-risk-" + horizonKey, point(center), props));
            metadata = metadata("selected_location_circle", "DERIVED", forecastOrigin(forecast, point),
                    "Forecast risk circle is derived from a real forecast AQI value and selected coordinates.");
        } else {
            features = List.of();
            metadata = metadata("unavailable", "UNAVAILABLE", "UNAVAILABLE", "No valid forecast AQI and coordinates were available for this horizon.");
        }
        return layer(type, displayName,
                "Forecast risk grid for " + horizonKey + " horizon.",
                confidence, generatedAt, features,
                legend("MODERATE", "#f6b93b", "HIGH", "#e55039", "SEVERE", "#b33939"),
                metadata,
                List.of("Predicted AQI " + horizonKey + ": " + (predicted != null ? predicted : "UNAVAILABLE"),
                        "Forecast trend: " + value(point != null ? point.getTrend() : null, value(forecast.getOverallTrend(), "unavailable"))));
    }

    private GeoSpatialLayer sourceZones(AttributionResult attribution, DecisionIntelligenceResult decision, Point center, Instant generatedAt) {
        List<PollutionSourceContribution> sources = attribution.getSources() != null ? attribution.getSources() : List.of();
        List<Map<String, Object>> features = new ArrayList<>();
        for (PollutionSourceContribution source : sources) {
            Map<String, Object> zone = source.getSourceZone() != null ? source.getSourceZone() : Map.of();
            if (!zone.containsKey("geometry") || source.getSourceType() == null || "UNKNOWN".equals(source.getSourceType().name())) {
                continue;
            }
            Map<String, Object> props = baseProperties(riskLevel(currentAqi(decision)), currentAqi(decision), 0,
                    source.getSourceType().name(), source.getContributionPercent(), source.getConfidence(),
                    "Review " + source.getSourceType().name() + " evidence before source controls",
                    "", evidenceSummary(source), source.getDatasetsUsed());
            props.put("signalType", source.getSignalType());
            props.put("geometrySource", source.getGeometrySource());
            props.put("limitations", source.getLimitations());
            props.put("timestamp", source.getTimestamp());
            features.add(enrichFeature(zone, props));
        }
        Map<String, Object> metadata = features.isEmpty()
                ? metadata("unavailable", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE", "No real source-zone geometry available; live mode does not generate synthetic source zones.")
                : metadata("attribution_real_geometry", "SUCCESS", "DERIVED_FROM_REAL_DATA", "Pollution source zones use geometry returned by evidence-based attribution.");
        return layer(GeoSpatialLayerType.POLLUTION_SOURCE_ZONES, "Pollution Source Zones",
                "Attribution-driven pollution source contribution zones.",
                attribution.getOverallConfidence(), generatedAt, features,
                legend("TRAFFIC", "#227093", "CONSTRUCTION", "#d99b18", "INDUSTRIAL", "#a93434"),
                metadata,
                List.of(value(attribution.getExplanation(), "Attribution evidence unavailable.")));
    }

    private GeoSpatialLayer trafficCorridors(CityEnvironmentalContext context, DecisionIntelligenceResult decision,
                                             Point center, Map<String, Object> osmData, Instant generatedAt) {
        double congestion = number(context.getTraffic() != null ? context.getTraffic().get("averageCongestionIndex") : null);
        List<Map<String, Object>> roadFeatures = osmFeatures(osmData, "roads");
        List<Map<String, Object>> features;
        Map<String, Object> metadata;
        double confidence = providerConfidence(context, "traffic", roadFeatures.isEmpty() ? 0.35 : 0.70);
        if (!roadFeatures.isEmpty()) {
            features = enrichFeatures(roadFeatures, baseProperties(riskLevel(currentAqi(decision)), currentAqi(decision), 0, dominant(decision), 0,
                    confidence, "Review traffic controls on mapped road corridor", "Traffic Police",
                    "OpenStreetMap road geometry used as an infrastructure proxy; not live traffic.", List.of("openstreetmap", "aqi")));
            metadata = metadata("openstreetmap", "SUCCESS", "OBSERVED_REAL_DATA", "Traffic corridors use OpenStreetMap road geometries as infrastructure proxy, not real-time traffic.");
        } else {
            features = List.of();
            metadata = metadata("unavailable", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE", "No OSM road geometry available; live mode does not generate synthetic traffic corridors.");
        }
        return layer(GeoSpatialLayerType.TRAFFIC_CORRIDORS, "Traffic Corridors",
                "Traffic corridor geometry from OpenStreetMap infrastructure proxy.",
                confidence, generatedAt, features,
                legend("CORRIDOR", "#227093", "CONGESTED", "#e55039", "MONITOR", "#596275"),
                metadata,
                List.of("OSM road features: " + roadFeatures.size(), "Signal type: INFRASTRUCTURE_PROXY"));
    }

    private GeoSpatialLayer constructionSites(CityEnvironmentalContext context, DecisionIntelligenceResult decision, Point center, Instant generatedAt) {
        List<Map<String, Object>> sites = context.getConstruction() != null ? context.getConstruction() : List.of();
        List<Map<String, Object>> imported = importedFeatures("construction");
        List<Map<String, Object>> features;
        Map<String, Object> metadata;
        double confidence = providerConfidence(context, "construction", sites.isEmpty() ? 0.25 : 0.65);
        if (!imported.isEmpty()) {
            features = enrichFeatures(imported, baseProperties(riskLevel(currentAqi(decision)), currentAqi(decision), 0, "CONSTRUCTION", 0,
                    Math.max(confidence, 0.72), "Inspect construction dust control", "Municipal Corporation",
                    "Imported construction GeoJSON layer", List.of("construction", "geojson", "aqi")));
            metadata = metadata("geojson_import", "SUCCESS", "OBSERVED_REAL_DATA", "Construction sites loaded from configured GeoJSON import.");
            confidence = Math.max(confidence, 0.72);
        } else {
            features = List.of();
            confidence = Math.min(confidence, 0.30);
            metadata = metadata("unavailable", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE", "No configured construction GeoJSON available; live mode does not generate synthetic construction sites.");
        }
        return layer(GeoSpatialLayerType.CONSTRUCTION_SITES, "Construction Sites",
                "Construction activity geometry from configured GeoJSON only.",
                confidence, generatedAt, features,
                legend("ACTIVE", "#d99b18", "HIGH_DUST", "#e55039", "FALLBACK", "#596275"),
                metadata,
                List.of("Construction permits in fusion context: " + sites.size(), "Imported construction features: " + imported.size()));
    }

    private GeoSpatialLayer industrialZones(CityEnvironmentalContext context, DecisionIntelligenceResult decision, Point center, Instant generatedAt) {
        List<Map<String, Object>> industries = context.getIndustries() != null ? context.getIndustries() : List.of();
        List<Map<String, Object>> imported = importedFeatures("industrial");
        List<Map<String, Object>> features = new ArrayList<>();
        double confidence = providerConfidence(context, "industries", industries.isEmpty() ? 0.25 : 0.70);
        Map<String, Object> metadata;
        if (!imported.isEmpty()) {
            features = enrichFeatures(imported, baseProperties(riskLevel(currentAqi(decision)), currentAqi(decision), 0, "INDUSTRIAL", 0,
                    Math.max(confidence, 0.78), "Inspect industrial emissions and compliance", "Pollution Control Board",
                    "Imported industrial GeoJSON layer", List.of("industries", "geojson", "aqi")));
            metadata = metadata("geojson_import", "SUCCESS", "OBSERVED_REAL_DATA", "Industrial polygons loaded from configured GeoJSON import.");
            confidence = Math.max(confidence, 0.78);
        } else {
            features = List.of();
            confidence = Math.min(confidence, 0.30);
            metadata = metadata("unavailable", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE", "No configured industrial GeoJSON available; live mode does not generate synthetic or provider-derived industrial zones.");
        }
        return layer(GeoSpatialLayerType.INDUSTRIAL_ZONES, "Industrial Zones",
                "Industrial source zones from provider coordinates where available.",
                confidence, generatedAt, features,
                legend("INDUSTRIAL", "#a93434", "NON_COMPLIANT", "#e55039", "MONITOR", "#596275"),
                metadata,
                List.of("Industrial source count: " + industries.size(), "Imported industrial features: " + imported.size()));
    }

    private GeoSpatialLayer sensitiveZones(CityEnvironmentalContext context, DecisionIntelligenceResult decision,
                                           Point center, Map<String, Object> osmData, Instant generatedAt) {
        Map<String, Object> population = context.getPopulation() != null ? context.getPopulation() : Map.of();
        int schools = (int) number(population.get("schoolsCount"));
        int hospitals = (int) number(population.get("hospitalsCount"));
        List<Map<String, Object>> sensitive = mergeFeatures(osmFeatures(osmData, "schools"), osmFeatures(osmData, "hospitals"));
        List<Map<String, Object>> features;
        Map<String, Object> metadata;
        double confidence = providerConfidence(context, "population", sensitive.isEmpty() ? 0.35 : 0.74);
        if (!sensitive.isEmpty()) {
            features = enrichFeatures(sensitive, baseProperties(riskLevel(currentAqi(decision)), currentAqi(decision), 0, dominant(decision), 0,
                    confidence, "Protect sensitive receptors during high AQI", "Health and Education Departments",
                    "OSM schools/hospitals geometry", List.of("openstreetmap", "population", "advisory", "aqi")));
            metadata = metadata("openstreetmap", "SUCCESS", "OBSERVED_REAL_DATA", "Sensitive zones use OpenStreetMap school and hospital geometry.");
        } else {
            features = List.of();
            metadata = metadata("unavailable", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE", "Sensitive-zone counts may be available, but no exact facility geometry was returned; live mode does not synthesize facilities.");
        }
        return layer(GeoSpatialLayerType.SENSITIVE_ZONES, "Sensitive Zones",
                "Schools and hospitals exposure layer.",
                confidence, generatedAt, features,
                legend("SCHOOL", "#218c74", "HOSPITAL", "#b33939", "FALLBACK", "#596275"),
                metadata,
                List.of("Schools count: " + schools, "Hospitals count: " + hospitals, "OSM sensitive features: " + sensitive.size()));
    }

    private GeoSpatialLayer windVector(CityEnvironmentalContext context, DecisionIntelligenceResult decision, Point center, Instant generatedAt) {
        Map<String, Object> wind = context.getWind() != null ? context.getWind() : Map.of();
        double speed = number(wind.get("speed"));
        double direction = number(wind.get("direction"));
        Point end = windEnd(center, direction, Math.max(0.018, Math.min(0.07, speed / 100.0 + 0.02)));
        List<Map<String, Object>> features = List.of(feature("wind-vector", line(center, end),
                baseProperties(riskLevel(currentAqi(decision)), currentAqi(decision), 0, dominant(decision), 0,
                        providerConfidence(context, "weather", 0.35), "Assess pollutant dispersion downwind", "City command center",
                        "Wind speed " + speed + " m/s, direction " + direction + " degrees", List.of("weather", "wind"))));
        return layer(GeoSpatialLayerType.WIND_VECTOR_LAYER, "Wind Vector Layer",
                "Wind direction and speed vector from fused weather context.",
                providerConfidence(context, "weather", 0.35), generatedAt, features,
                legend("WIND", "#218c74", "LOW_DISPERSION", "#e55039", "NORMAL", "#227093"),
                metadata("weather_vector", speed > 0 || direction > 0 ? "SUCCESS" : "UNAVAILABLE", speed > 0 || direction > 0 ? "OBSERVED_REAL_DATA" : "UNAVAILABLE", "Vector anchored at selected location using real weather wind speed/direction."),
                List.of("Wind speed: " + speed, "Wind direction: " + direction));
    }

    private GeoSpatialLayer greenCover(CityEnvironmentalContext context, DecisionIntelligenceResult decision,
                                       Point center, Map<String, Object> osmData, Instant generatedAt) {
        double greenIndex = number(context.getGreenCover() != null ? context.getGreenCover().get("greenCoverIndex") : null);
        List<Map<String, Object>> naturalFeatures = mergeFeatures(osmFeatures(osmData, "parks"), osmFeatures(osmData, "water"), importedFeatures("parks"), importedFeatures("water"));
        List<Map<String, Object>> features;
        Map<String, Object> metadata;
        double confidence = providerConfidence(context, "greenCover", naturalFeatures.isEmpty() ? 0.20 : 0.70);
        if (!naturalFeatures.isEmpty()) {
            features = enrichFeatures(naturalFeatures, baseProperties(riskLevel(currentAqi(decision)), currentAqi(decision), 0, dominant(decision), 0,
                    confidence, "Prioritize green buffer action in low-cover zones", "Urban Forestry Department",
                    "OSM/imported parks and water-body geometry", List.of("openstreetmap", "greenCover", "satellite")));
            metadata = metadata(sourceName(naturalFeatures), "SUCCESS", "OBSERVED_REAL_DATA", "Green cover layer uses OSM or imported parks/water bodies.");
        } else {
            features = List.of();
            metadata = metadata("unavailable", greenIndex > 0 ? "PARTIAL" : "UNAVAILABLE", greenIndex > 0 ? "DERIVED_FROM_REAL_DATA" : "UNAVAILABLE", "Green-cover measurement has no real polygon geometry; live mode does not synthesize green-cover zones.");
        }
        return layer(GeoSpatialLayerType.GREEN_COVER_LAYER, "Green Cover Layer",
                "Green cover context for mitigation planning.",
                confidence, generatedAt, features,
                legend("LOW_COVER", "#d99b18", "GREEN_BUFFER", "#218c74", "FALLBACK", "#596275"),
                metadata,
                List.of("Green cover index: " + greenIndex, "Real green/water features: " + naturalFeatures.size()));
    }

    private GeoSpatialLayer satelliteEvidence(CityEnvironmentalContext context, DecisionIntelligenceResult decision, Point center, Instant generatedAt) {
        Map<String, Object> satellite = context.getSatellite() != null ? context.getSatellite() : Map.of();
        boolean measurementFallback = Boolean.TRUE.equals(satellite.get("measurementFallback"));
        String status = measurementFallback || satellite.isEmpty() ? "FALLBACK" : "SUCCESS";
        List<Map<String, Object>> features = List.of();
        return layer(GeoSpatialLayerType.SATELLITE_EVIDENCE_LAYER, "Satellite Evidence Layer",
                "Earth Engine or satellite-derived evidence layer.",
                providerConfidence(context, "satellite", measurementFallback ? 0.35 : 0.70), generatedAt, features,
                legend("SATELLITE", "#596275", "THERMAL", "#e55039", "FALLBACK", "#d99b18"),
                metadata("earth_engine", status, measurementFallback ? "DERIVED_FROM_REAL_DATA" : "OBSERVED_REAL_DATA", measurementFallback ? "Satellite provider used measurement-level fallback with no map geometry." : "Satellite measurements are available; no synthetic live geometry is generated."),
                List.of("Satellite available: " + satellite.getOrDefault("available", false), "Measurement fallback: " + measurementFallback));
    }

    private GeoSpatialLayer layer(GeoSpatialLayerType type, String displayName, String description, double confidence,
                                  Instant generatedAt, List<Map<String, Object>> features,
                                  List<Map<String, Object>> legend, Map<String, Object> metadata, List<String> evidence) {
        return GeoSpatialLayer.builder()
                .layerId(type.name())
                .layerType(type)
                .displayName(displayName)
                .description(description)
                .confidence(round(clamp(confidence, 0.05, 0.98)))
                .generatedAt(generatedAt)
                .geoJson(featureCollection(features))
                .legend(legend != null ? legend : List.of())
                .metadata(metadata != null ? metadata : Map.of())
                .evidence(evidence != null ? evidence : List.of())
                .build();
    }

    private Map<String, Object> featureCollection(List<Map<String, Object>> features) {
        return Map.of("type", "FeatureCollection", "features", features != null ? features : List.of());
    }

    private Map<String, Object> feature(String id, Map<String, Object> geometry, Map<String, Object> properties) {
        Map<String, Object> props = new LinkedHashMap<>(properties != null ? properties : Map.of());
        props.put("featureId", id);
        return Map.of("type", "Feature", "id", id, "geometry", geometry, "properties", props);
    }

    private Map<String, Object> openStreetMapData(Point center) {
        if (openStreetMapDataService == null) return Map.of();
        return openStreetMapDataService.fetch(center.lat(), center.lng()).orElse(Map.of());
    }

    private List<Map<String, Object>> osmFeatures(Map<String, Object> osmData, String layerKey) {
        if (openStreetMapDataService == null || osmData == null || osmData.isEmpty()) return List.of();
        List<Map<String, Object>> features = openStreetMapDataService.features(osmData, layerKey);
        return features != null ? features : List.of();
    }

    private List<Map<String, Object>> importedFeatures(String layerKey) {
        if (geoJsonImportService == null) return List.of();
        return geoJsonImportService.load(layerKey)
                .map(geoJson -> {
                    Object features = geoJson.get("features");
                    if (features instanceof List<?> list) {
                        List<Map<String, Object>> result = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> map) {
                                Map<String, Object> feature = new LinkedHashMap<>();
                                map.forEach((key, value) -> feature.put(String.valueOf(key), value));
                                result.add(feature);
                            }
                        }
                        return result;
                    }
                    return List.<Map<String, Object>>of();
                })
                .orElse(List.of());
    }

    @SafeVarargs
    private final List<Map<String, Object>> mergeFeatures(List<Map<String, Object>>... featureLists) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (List<Map<String, Object>> featureList : featureLists) {
            if (featureList != null) merged.addAll(featureList);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> enrichFeatures(List<Map<String, Object>> features, Map<String, Object> standardProperties) {
        return features.stream()
                .limit(200)
                .map(feature -> {
                    Map<String, Object> copy = new LinkedHashMap<>(feature);
                    Map<String, Object> props = new LinkedHashMap<>(standardProperties != null ? standardProperties : Map.of());
                    Object existing = feature.get("properties");
                    if (existing instanceof Map<?, ?> map) {
                        map.forEach((key, value) -> props.putIfAbsent(String.valueOf(key), value));
                    }
                    if (!props.containsKey("featureId")) {
                        props.put("featureId", String.valueOf(feature.getOrDefault("id", "real-data-feature")));
                    }
                    copy.put("properties", props);
                    return copy;
                })
                .toList();
    }

    private Map<String, Object> enrichFeature(Map<String, Object> feature, Map<String, Object> standardProperties) {
        Map<String, Object> copy = new LinkedHashMap<>(feature != null ? feature : Map.of());
        Map<String, Object> props = new LinkedHashMap<>(standardProperties != null ? standardProperties : Map.of());
        Object existing = copy.get("properties");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> props.putIfAbsent(String.valueOf(key), value));
        }
        if (!props.containsKey("featureId")) {
            props.put("featureId", String.valueOf(copy.getOrDefault("id", "attribution-real-source-zone")));
        }
        copy.put("type", copy.getOrDefault("type", "Feature"));
        copy.put("properties", props);
        return copy;
    }

    private String sourceName(List<Map<String, Object>> features) {
        boolean hasOsm = features.stream()
                .map(feature -> asMap(feature.get("properties")))
                .anyMatch(properties -> "openstreetmap".equals(properties.get("source")));
        return hasOsm ? "openstreetmap" : "geojson_import";
    }

    private Map<String, Object> baseProperties(String riskLevel, int aqi, int predictedAqi, String dominantSource,
                                               int contributionPercent, double confidence, String recommendation,
                                               String responsibleAgency, String evidenceSummary, List<String> datasetsUsed) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("riskLevel", value(riskLevel, "UNKNOWN"));
        props.put("aqi", aqi);
        props.put("predictedAqi", predictedAqi);
        props.put("dominantSource", value(dominantSource, "UNKNOWN"));
        props.put("contributionPercent", contributionPercent);
        props.put("confidence", round(clamp(confidence, 0.05, 0.98)));
        props.put("recommendation", value(recommendation, ""));
        props.put("responsibleAgency", value(responsibleAgency, ""));
        props.put("evidenceSummary", value(evidenceSummary, ""));
        props.put("datasetsUsed", datasetsUsed != null ? datasetsUsed : List.of());
        return props;
    }

    private Map<String, Object> point(Point point) {
        return Map.of("type", "Point", "coordinates", List.of(point.lng(), point.lat()));
    }

    private Map<String, Object> line(Point start, Point end) {
        return Map.of("type", "LineString", "coordinates", List.of(List.of(start.lng(), start.lat()), List.of(end.lng(), end.lat())));
    }

    private Map<String, Object> polygon(Point center, double size) {
        List<List<Double>> ring = List.of(
                List.of(center.lng() - size, center.lat() - size),
                List.of(center.lng() + size, center.lat() - size),
                List.of(center.lng() + size, center.lat() + size),
                List.of(center.lng() - size, center.lat() + size),
                List.of(center.lng() - size, center.lat() - size)
        );
        return Map.of("type", "Polygon", "coordinates", List.of(ring));
    }

    private List<Map<String, Object>> legend(String label1, String color1, String label2, String color2, String label3, String color3) {
        return List.of(
                Map.of("label", label1, "color", color1),
                Map.of("label", label2, "color", color2),
                Map.of("label", label3, "color", color3)
        );
    }

    private Map<String, Object> metadata(String geometrySource, String status, String dataOrigin, String note) {
        return Map.of("geometrySource", geometrySource, "status", status, "dataOrigin", dataOrigin, "note", note);
    }

    private Point center(CityEnvironmentalContext context) {
        return coordinate(context.getCoordinates()).orElse(new Point(0.0, 0.0));
    }

    private java.util.Optional<Point> coordinate(Map<String, Object> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) return java.util.Optional.empty();
        double lat = firstPositive(number(coordinates.get("latitude")), number(coordinates.get("lat")));
        double lng = firstPositive(number(coordinates.get("longitude")), number(coordinates.get("lng")), number(coordinates.get("lon")));
        return lat != 0.0 && lng != 0.0 ? java.util.Optional.of(new Point(lat, lng)) : java.util.Optional.empty();
    }

    private Point offset(Point center, int index, double scale) {
        return new Point(
                center.lat() + Math.sin(index * 1.7) * scale,
                center.lng() + Math.cos(index * 1.4) * scale
        );
    }

    private Point windEnd(Point center, double directionDegrees, double scale) {
        double radians = Math.toRadians(directionDegrees);
        return new Point(center.lat() + Math.cos(radians) * scale, center.lng() + Math.sin(radians) * scale);
    }

    private int currentAqi(CityEnvironmentalContext context, EnforcementResult enforcement) {
        if (enforcement != null && enforcement.getCurrentAqi() != null && enforcement.getCurrentAqi() > 0) return enforcement.getCurrentAqi();
        Map<String, Object> aqi = context.getAqi() != null ? context.getAqi() : Map.of();
        return (int) firstPositive(number(aqi.get("currentAqi")), number(aqi.get("aqi")));
    }

    private int currentAqi(DecisionIntelligenceResult decision) {
        return decision != null && decision.getCurrentAQI() != null && decision.getCurrentAQI() > 0 ? decision.getCurrentAQI() : 0;
    }

    private String dominant(AttributionResult attribution, DecisionIntelligenceResult decision) {
        if (attribution != null && attribution.getDominantSource() != null) return attribution.getDominantSource().name();
        return dominant(decision);
    }

    private String dominant(ForecastPoint point, DecisionIntelligenceResult decision) {
        if (point != null && point.getExpectedDominantSource() != null) return point.getExpectedDominantSource().name();
        return dominant(decision);
    }

    private String dominant(DecisionIntelligenceResult decision) {
        if (decision != null && decision.getAttribution() != null && decision.getAttribution().getDominantSource() != null) {
            return decision.getAttribution().getDominantSource().name();
        }
        return "UNKNOWN";
    }

    private String evidenceSummary(PollutionSourceContribution source) {
        if (source.getEvidence() == null || source.getEvidence().isEmpty()) {
            return "Contribution " + source.getContributionPercent() + "%";
        }
        return source.getEvidence().stream()
                .map(Object::toString)
                .limit(2)
                .reduce((a, b) -> a + "; " + b)
                .orElse("Contribution " + source.getContributionPercent() + "%");
    }

    private String explanation(ForecastPoint point) {
        if (point == null || point.getExplanation() == null) return "Forecast explanation unavailable";
        return value(point.getExplanation().getSummary(), "Forecast explanation unavailable");
    }

    private List<String> datasets(ForecastPoint point) {
        if (point == null || point.getExplanation() == null || point.getExplanation().getDataUsed() == null) {
            return List.of("forecast");
        }
        return point.getExplanation().getDataUsed();
    }

    private int forecastPeak(DecisionIntelligenceResult decision) {
        ForecastResult forecast = decision != null ? decision.getForecast() : null;
        return forecast != null && forecast.getForecast() != null
                ? forecast.getForecast().values().stream()
                        .map(ForecastPoint::getPredictedAqi)
                        .filter(value -> value != null && value > 0)
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(0)
                : 0;
    }

    private boolean validCenter(Point center) {
        return center != null && center.lat() != 0.0 && center.lng() != 0.0;
    }

    private int hotspotRadiusMeters(int aqi) {
        return (int) Math.round(clamp(900 + aqi * 7.5, 1200, 4500));
    }

    private int forecastRadiusMeters(GeoSpatialLayerType type, int predictedAqi) {
        int horizonBoost = switch (type) {
            case FORECAST_GRID_48H -> 500;
            case FORECAST_GRID_72H -> 900;
            default -> 0;
        };
        return (int) Math.round(clamp(1000 + predictedAqi * 6.0 + horizonBoost, 1200, 5200));
    }

    private String forecastOrigin(ForecastResult forecast, ForecastPoint point) {
        String origin = point != null ? value(point.getDataOrigin(), value(point.getEngine(), value(point.getMode(), ""))) : "";
        if (!origin.isBlank()) return origin;
        if (forecast != null && forecast.isFallbackUsed()) return "PERSISTENCE_FALLBACK";
        return value(forecast != null ? value(forecast.getEngine(), forecast.getMode()) : "", "PROVIDER_FORECAST");
    }

    private double providerConfidence(CityEnvironmentalContext context, String provider, double fallback) {
        if (context.getProviderConfidence() != null && context.getProviderConfidence().containsKey(provider)) {
            return context.getProviderConfidence().get(provider);
        }
        return fallback;
    }

    private String riskLevel(int aqi) {
        if (aqi <= 50) return "GOOD";
        if (aqi <= 100) return "NORMAL";
        if (aqi <= 200) return "ELEVATED";
        if (aqi <= 300) return "HIGH";
        if (aqi <= 400) return "SEVERE";
        return "EMERGENCY";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String value(String value, String fallback) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value) ? value : fallback;
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private double firstPositive(double... values) {
        for (double value : values) {
            if (value > 0) return value;
        }
        return 0.0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Point(double lat, double lng) {}

    private record CacheEntry(GeoSpatialIntelligenceResult result, Instant cachedAt) {
        boolean isExpired(long ttlSeconds) {
            return cachedAt.plusSeconds(ttlSeconds).isBefore(Instant.now());
        }
    }
}

