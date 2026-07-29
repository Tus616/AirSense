package com.airsense.api.decision;

import com.airsense.api.advisory.HealthAdvisory;
import com.airsense.api.advisory.HealthAdvisoryRequest;
import com.airsense.api.advisory.HealthAdvisoryResult;
import com.airsense.api.advisory.HealthAdvisoryService;
import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.attribution.PollutionAttributionService;
import com.airsense.api.enforcement.EnforcementIntelligenceService;
import com.airsense.api.enforcement.EnforcementRecommendation;
import com.airsense.api.enforcement.EnforcementRequest;
import com.airsense.api.enforcement.EnforcementResult;
import com.airsense.api.forecast.ForecastOrchestrator;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastRequest;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.forecast.ForecastSnapshotService;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import com.airsense.api.explainability.ExplainabilitySummary;
import com.airsense.api.geospatial.GeoSpatialSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionIntelligenceService {
    private final DataFusionService dataFusionService;
    private final PollutionAttributionService attributionService;
    @Qualifier("hyperlocalForecastOrchestrator")
    private final ForecastOrchestrator forecastOrchestrator;
    @Qualifier("phase24EnforcementIntelligenceService")
    private final EnforcementIntelligenceService enforcementService;
    private final HealthAdvisoryService healthAdvisoryService;
    @Autowired(required = false)
    private ForecastSnapshotService forecastSnapshotService;

    public DecisionIntelligenceResult decide(DecisionRequest request) {
        DecisionRequest normalized = (request != null ? request : DecisionRequest.builder().build()).normalized();
        Map<String, String> failures = new LinkedHashMap<>();
        CityEnvironmentalContext context = collectFusion(normalized, failures);
        AttributionResult attribution = collectAttribution(context, normalized, failures);
        ForecastResult forecast = collectForecast(context, normalized, attribution, failures);
        EnforcementResult enforcement = collectEnforcement(context, attribution, forecast, normalized, failures);
        HealthAdvisoryResult advisories = collectAdvisories(context, attribution, forecast, enforcement, normalized, failures);
        DecisionIntelligenceResult result = assemble(context, attribution, forecast, enforcement, advisories, normalized, failures);
        persistForecastSnapshot(result);
        return result;
    }

    public DecisionIntelligenceResult assemble(CityEnvironmentalContext context, AttributionResult attribution,
                                               ForecastResult forecast, EnforcementResult enforcement,
                                               HealthAdvisoryResult advisories, DecisionRequest request,
                                               Map<String, String> failures) {
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(request != null ? request.getCityId() : "UNKNOWN_PLACE");
        DecisionRequest safeRequest = (request != null ? request : DecisionRequest.builder().build()).normalized();
        AttributionResult safeAttribution = attribution != null ? attribution : emptyAttribution(safeContext, safeRequest);
        ForecastResult safeForecast = forecast != null ? forecast : emptyForecast(safeContext, safeRequest);
        EnforcementResult safeEnforcement = enforcement != null ? enforcement : emptyEnforcement(safeContext, safeRequest);
        HealthAdvisoryResult safeAdvisories = advisories != null ? advisories : emptyAdvisory(safeContext, safeRequest);
        Map<String, String> safeFailures = failures != null ? failures : Map.of();

        Integer canonicalCurrentAqi = canonicalCurrentAqi(safeContext);
        int currentAqi = canonicalCurrentAqi != null ? canonicalCurrentAqi : 0;
        Integer forecastPeak = firstPositiveInteger(safeEnforcement.getForecastPeakAqi(), safeAdvisories.getForecastPeakAqi(), forecastPeak(safeForecast));
        PollutionSourceType source = source(safeAttribution, safeEnforcement, safeAdvisories);
        RiskAssessment risk = riskAssessment(safeContext, currentAqi, forecastPeak, source);
        List<PriorityAction> priorityActions = priorityActions(safeEnforcement, safeAdvisories, currentAqi, forecastPeak);
        EvidenceBundle evidence = evidenceBundle(safeContext, safeAttribution, safeForecast, safeEnforcement, safeAdvisories);
        EngineStatus status = engineStatus(safeContext, safeAttribution, safeForecast, safeEnforcement, safeAdvisories, safeFailures);
        DecisionSummary summary = summary(safeContext, safeAttribution, safeForecast, safeEnforcement, safeAdvisories, canonicalCurrentAqi, currentAqi, forecastPeak, source, priorityActions);
        double confidence = overallConfidence(evidence, status);
        Instant generatedAt = Instant.now();
        String snapshotId = snapshotId(safeContext, safeAttribution);
        Map<String, Object> signals = environmentalSignals(safeContext);
        Map<String, ModuleStatus> moduleStatuses = moduleStatuses(safeContext, safeAttribution, safeForecast,
                safeEnforcement, safeAdvisories, status, safeFailures, canonicalCurrentAqi, forecastPeak, confidence,
                snapshotId, generatedAt, signals);

        log.info("DecisionIntelligence Success cityId={} degraded={} currentAqi={} forecastPeak={}",
                valueOrDefault(safeContext.getCityId(), safeRequest.getCityId()), status.isDegradedMode(), currentAqi, forecastPeak);
        return DecisionIntelligenceResult.builder()
                .city(valueOrDefault(safeContext.getCity(), safeRequest.getCityId()))
                .cityId(valueOrDefault(safeContext.getCityId(), safeRequest.getCityId()))
                .generatedAt(generatedAt)
                .snapshotId(snapshotId)
                .sharedSnapshot(sharedSnapshot(safeContext, safeForecast, safeAttribution, safeRequest))
                .locationKey(safeAttribution.getLocationKey())
                .snapshotObservedAt(safeAttribution.getSnapshotObservedAt())
                .snapshotGeneratedAt(safeAttribution.getSnapshotGeneratedAt())
                .snapshotReused(Boolean.TRUE.equals(safeAttribution.getSnapshotReused())
                        || Boolean.TRUE.equals(safeContext.getMetadata().get("snapshotReused")))
                .locationHash(locationHash(safeContext, safeAttribution))
                .summary(summary)
                .riskAssessment(risk)
                .currentAQI(canonicalCurrentAqi)
                .forecast(safeForecast)
                .attribution(safeAttribution)
                .enforcement(safeEnforcement)
                .advisories(safeAdvisories)
                .priorityActions(priorityActions)
                .evidenceBundle(evidence)
                .engineStatus(status)
                .overallConfidence(confidence)
                .geospatialSummary(GeoSpatialSummary.builder()
                        .layerCount(12)
                        .geometrySource("geospatial_endpoint")
                        .confidence(confidence)
                        .degradedMode(status.isDegradedMode())
                        .build())
                .geospatialEndpoint("/api/v1/intelligence/geospatial?cityId=" + valueOrDefault(safeContext.getCityId(), safeRequest.getCityId()))
                .explainabilitySummary(ExplainabilitySummary.builder()
                        .explainabilityScore(0.0)
                        .overallConfidence(confidence)
                        .evidenceCount(evidence.getDatasetsUsed() != null ? evidence.getDatasetsUsed().size() : 0)
                        .reasoningStepCount(0)
                        .degradedMode(status.isDegradedMode())
                        .build())
                .explainabilityEndpoint("/api/v1/intelligence/explainability?cityId=" + valueOrDefault(safeContext.getCityId(), safeRequest.getCityId()))
                .moduleStatuses(moduleStatuses)
                .environmentalSignals(signals)
                .build();
    }

    private CityEnvironmentalContext collectFusion(DecisionRequest request, Map<String, String> failures) {
        try {
            CityEnvironmentalContext context = dataFusionService.buildContext(FusionRequest.builder()
                    .cityId(request.getCityId())
                    .placeId(request.getPlaceId())
                    .cityName(request.getCityName())
                    .state(request.getState())
                    .country(request.getCountry())
                    .latitude(request.getLatitude())
                    .longitude(request.getLongitude())
                    .parameters(Map.of("refresh", request.isRefresh()))
                    .build());
            return context != null ? context : CityEnvironmentalContext.empty(request.getCityId());
        } catch (Exception e) {
            failures.put("fusion", reason(e));
            log.warn("DecisionIntelligence Fusion Failed cityId={} reason={}", request.getCityId(), e.getMessage());
            return CityEnvironmentalContext.empty(request.getCityId());
        }
    }

    private AttributionResult collectAttribution(CityEnvironmentalContext context, DecisionRequest request, Map<String, String> failures) {
        try {
            return attributionService.attribute(context, AttributionRequest.builder()
                    .cityId(request.getCityId())
                    .placeId(request.getPlaceId())
                    .cityName(request.getCityName())
                    .state(request.getState())
                    .country(request.getCountry())
                    .wardId(request.getWardId())
                    .latitude(request.getLatitude())
                    .longitude(request.getLongitude())
                    .refreshEvidence(request.isRefresh())
                    .build());
        } catch (Exception e) {
            failures.put("attribution", reason(e));
            log.warn("DecisionIntelligence Attribution Failed cityId={} reason={}", request.getCityId(), e.getMessage());
            return emptyAttribution(context, request);
        }
    }

    private ForecastResult collectForecast(CityEnvironmentalContext context, DecisionRequest request,
                                           AttributionResult attribution, Map<String, String> failures) {
        try {
            return forecastOrchestrator.forecast(context, ForecastRequest.builder()
                    .cityId(request.getCityId())
                    .placeId(request.getPlaceId())
                    .cityName(request.getCityName())
                    .state(request.getState())
                    .country(request.getCountry())
                    .wardId(request.getWardId())
                    .latitude(request.getLatitude())
                    .longitude(request.getLongitude())
                    .build(), attribution);
        } catch (Exception e) {
            failures.put("forecast", reason(e));
            log.warn("DecisionIntelligence Forecast Failed cityId={} reason={}", request.getCityId(), e.getMessage());
            return emptyForecast(context, request);
        }
    }

    private EnforcementResult collectEnforcement(CityEnvironmentalContext context, AttributionResult attribution,
                                                 ForecastResult forecast, DecisionRequest request,
                                                 Map<String, String> failures) {
        try {
            return enforcementService.recommend(context, attribution, forecast, EnforcementRequest.builder()
                    .cityId(request.getCityId())
                    .placeId(request.getPlaceId())
                    .cityName(request.getCityName())
                    .state(request.getState())
                    .country(request.getCountry())
                    .wardId(request.getWardId())
                    .latitude(request.getLatitude())
                    .longitude(request.getLongitude())
                    .build());
        } catch (Exception e) {
            failures.put("enforcement", reason(e));
            log.warn("DecisionIntelligence Enforcement Failed cityId={} reason={}", request.getCityId(), e.getMessage());
            return emptyEnforcement(context, request);
        }
    }

    private HealthAdvisoryResult collectAdvisories(CityEnvironmentalContext context, AttributionResult attribution,
                                                   ForecastResult forecast, EnforcementResult enforcement,
                                                   DecisionRequest request, Map<String, String> failures) {
        try {
            return healthAdvisoryService.generate(context, attribution, forecast, enforcement, HealthAdvisoryRequest.builder()
                    .cityId(request.getCityId())
                    .placeId(request.getPlaceId())
                    .cityName(request.getCityName())
                    .state(request.getState())
                    .country(request.getCountry())
                    .wardId(request.getWardId())
                    .latitude(request.getLatitude())
                    .longitude(request.getLongitude())
                    .snapshotId(snapshotId(context, attribution))
                    .build());
        } catch (Exception e) {
            failures.put("advisory", reason(e));
            log.warn("DecisionIntelligence Advisory Failed cityId={} reason={}", request.getCityId(), e.getMessage());
            return emptyAdvisory(context, request);
        }
    }

    private DecisionSummary summary(CityEnvironmentalContext context, AttributionResult attribution,
                                    ForecastResult forecast, EnforcementResult enforcement,
                                    HealthAdvisoryResult advisories, Integer displayCurrentAqi,
                                    int currentAqi, Integer forecastPeak,
                                    PollutionSourceType source, List<PriorityAction> actions) {
        String risk = riskLevel(Math.max(currentAqi, forecastPeak != null ? forecastPeak : currentAqi));
        String currentLabel = displayCurrentAqi != null && displayCurrentAqi > 0 ? String.valueOf(displayCurrentAqi) : "unavailable";
        boolean forecastUnavailable = forecast == null || "UNAVAILABLE".equalsIgnoreCase(valueOrDefault(forecast.getMode(), forecast.getModelVersion()));
        String next = forecastUnavailable
                ? "Forecast is unavailable until a genuinely trained and evaluated model exists."
                : "Forecast is " + valueOrDefault(forecast.getOverallTrend(), forecastPeak != null && forecastPeak > currentAqi ? "worsening" : forecastPeak != null && forecastPeak < currentAqi ? "improving" : "stable")
                        + " with peak AQI around " + (forecastPeak != null && forecastPeak > 0 ? forecastPeak : "unavailable") + ".";
        String officialAction = actions.stream()
                .filter(action -> "enforcement".equals(action.getSourceEngine()))
                .map(PriorityAction::getMessage)
                .findFirst()
                .orElse("Continue monitoring and prepare targeted response if AQI worsens.");
        String citizenAction = actions.stream()
                .filter(action -> "advisory".equals(action.getSourceEngine()))
                .map(PriorityAction::getMessage)
                .findFirst()
                .orElse("Follow AQI-based exposure precautions and watch for updated advisories.");
        boolean uncertainSource = source == PollutionSourceType.UNKNOWN || attribution.getOverallConfidence() < 0.35;
        String why = uncertainSource
                ? "Source attribution is uncertain. " + valueOrDefault(attribution.getExplanation(), "UNKNOWN share is retained because evidence is incomplete.")
                : "Evidence-supported source signal is " + source + ". " + valueOrDefault(attribution.getExplanation(), "Attribution evidence is limited.");
        return DecisionSummary.builder()
                .whatIsHappening("Current AQI is " + currentLabel + " with " + risk + " overall risk.")
                .whyIsItHappening(why)
                .whatWillHappenNext(next)
                .whatShouldOfficialsDoNow(officialAction)
                .whatShouldCitizensDoNow(citizenAction)
                .build();
    }

    private RiskAssessment riskAssessment(CityEnvironmentalContext context, int currentAqi, Integer forecastPeak, PollutionSourceType source) {
        Map<String, Object> population = safeMap(context.getPopulation());
        int schools = (int) number(population.get("schoolsCount"));
        int hospitals = (int) number(population.get("hospitalsCount"));
        double populationCount = firstPositive(number(population.get("populationDensity")), number(population.get("density")), number(population.get("population")));
        String sourceRisk = switch (source) {
            case TRAFFIC, ROAD_DUST_CONSTRUCTION -> "LOCALIZED_HIGH";
            case INDUSTRIAL, BIOMASS_WASTE_BURNING -> "SOURCE_CRITICAL";
            case SECONDARY_AEROSOL_OR_OTHER -> "DISPERSION_RISK";
            case RESIDENTIAL_COMBUSTION -> "NEIGHBORHOOD_RISK";
            default -> "UNKNOWN";
        };
        return RiskAssessment.builder()
                .currentRisk(riskLevel(currentAqi))
                .forecastRisk(forecastPeak != null ? riskLevel(forecastPeak) : "UNAVAILABLE")
                .dominantSourceRisk(sourceRisk)
                .populationExposureRisk(populationCount >= 1_000_000 || populationCount >= 10000 ? "HIGH" : populationCount > 0 ? "MODERATE" : "UNKNOWN")
                .sensitiveZoneRisk(schools > 0 || hospitals > 0 ? "HIGH" : "UNKNOWN")
                .overallRiskLevel(riskLevel(Math.max(currentAqi, forecastPeak != null ? forecastPeak : currentAqi)))
                .build();
    }

    private List<PriorityAction> priorityActions(EnforcementResult enforcement, HealthAdvisoryResult advisories, int currentAqi, Integer forecastPeak) {
        List<PriorityAction> actions = new ArrayList<>();
        if (enforcement.getRecommendations() != null) {
            enforcement.getRecommendations().stream()
                    .sorted(Comparator.comparingInt(EnforcementRecommendation::getPriorityScore).reversed())
                    .limit(5)
                    .forEach(rec -> actions.add(PriorityAction.builder()
                            .sourceEngine("enforcement")
                            .actionType(rec.getActionType() != null ? rec.getActionType().name() : "ENFORCEMENT_ACTION")
                            .targetAudience("officials")
                            .responsibleParty(valueOrDefault(rec.getResponsibleAgency(), "City administration"))
                            .message(valueOrDefault(rec.getReason(), "Review enforcement recommendation"))
                            .urgency(valueOrDefault(rec.getUrgency(), "MONITOR"))
                            .priorityScore(rec.getPriorityScore())
                            .confidence(rec.getConfidence())
                            .build()));
        }
        if (advisories.getAdvisories() != null) {
            advisories.getAdvisories().stream()
                    .sorted(Comparator.comparingInt((HealthAdvisory advisory) -> advisory.getSeverity() != null ? advisory.getSeverity().ordinal() : 0).reversed())
                    .limit(5)
                    .forEach(advisory -> actions.add(PriorityAction.builder()
                            .sourceEngine("advisory")
                            .actionType("HEALTH_ADVISORY")
                            .targetAudience(advisory.getTargetGroup() != null ? advisory.getTargetGroup().name() : "CITIZENS")
                            .responsibleParty("Citizens and public institutions")
                            .message(valueOrDefault(advisory.getTitle(), advisory.getMessage()))
                            .urgency(advisory.getSeverity() != null && advisory.getSeverity().ordinal() >= 3 ? "IMMEDIATE" : "TODAY")
                            .priorityScore(advisory.getSeverity() != null ? advisory.getSeverity().ordinal() * 20 + 20 : 20)
                            .confidence(advisory.getConfidence())
                            .build()));
        }
        if (Math.max(currentAqi, forecastPeak != null ? forecastPeak : currentAqi) >= 300) {
            actions.add(PriorityAction.builder()
                    .sourceEngine("decision")
                    .actionType("URGENT_ALERT")
                    .targetAudience("officials_and_citizens")
                    .responsibleParty("City command center")
                    .message("Severe AQI risk: coordinate health, school, traffic, and municipal response immediately.")
                    .urgency("IMMEDIATE")
                    .priorityScore(100)
                    .confidence(0.85)
                    .build());
        }
        return dedupe(actions).stream()
                .sorted(Comparator.comparingInt(PriorityAction::getPriorityScore).reversed())
                .limit(10)
                .toList();
    }

    private EvidenceBundle evidenceBundle(CityEnvironmentalContext context, AttributionResult attribution,
                                          ForecastResult forecast, EnforcementResult enforcement,
                                          HealthAdvisoryResult advisories) {
        Set<String> datasets = new LinkedHashSet<>();
        datasets.addAll(context.getProviderStatus() != null ? context.getProviderStatus().keySet() : Set.of());
        if (enforcement.getRecommendations() != null) {
            enforcement.getRecommendations().forEach(rec -> datasets.addAll(rec.getDatasetsUsed() != null ? rec.getDatasetsUsed() : List.of()));
        }
        if (advisories.getAdvisories() != null) {
            advisories.getAdvisories().forEach(advisory -> {
                if (advisory.getEvidence() != null) {
                    advisory.getEvidence().forEach(evidence -> datasets.add(evidence.getDataset()));
                }
            });
        }
        Map<String, Double> confidence = new LinkedHashMap<>();
        confidence.put("fusion", average(context.getProviderConfidence()));
        confidence.put("attribution", attribution.getOverallConfidence());
        confidence.put("forecast", forecast.getOverallConfidence());
        confidence.put("enforcement", enforcementConfidence(enforcement));
        confidence.put("advisory", advisoryConfidence(advisories));

        List<String> explanations = new ArrayList<>();
        if (attribution.getExplanation() != null && !attribution.getExplanation().isBlank()) explanations.add(attribution.getExplanation());
        if (forecast.getOverallTrend() != null) explanations.add("Forecast trend: " + forecast.getOverallTrend());
        if (enforcement.getRecommendations() != null) {
            enforcement.getRecommendations().stream().limit(3).map(EnforcementRecommendation::getReason).filter(v -> v != null && !v.isBlank()).forEach(explanations::add);
        }
        if (advisories.getAdvisories() != null) {
            advisories.getAdvisories().stream().limit(3).map(HealthAdvisory::getMessage).filter(v -> v != null && !v.isBlank()).forEach(explanations::add);
        }
        return EvidenceBundle.builder()
                .datasetsUsed(new ArrayList<>(datasets))
                .confidenceScores(confidence)
                .providerStatus(context.getProviderStatus() != null ? context.getProviderStatus() : Map.of())
                .explanations(explanations)
                .build();
    }

    private EngineStatus engineStatus(CityEnvironmentalContext context, AttributionResult attribution, ForecastResult forecast,
                                      EnforcementResult enforcement, HealthAdvisoryResult advisories,
                                      Map<String, String> failures) {
        String fusionStatus = failures.containsKey("fusion") ? "FAILED" : context.getProviderStatus() != null && !context.getProviderStatus().isEmpty() ? "SUCCESS" : "PARTIAL";
        String forecastMode = forecast.getMode() != null ? forecast.getMode() : forecast.getModelVersion();
        String forecastStatus = failures.containsKey("forecast") ? "FAILED"
                : "UNAVAILABLE".equalsIgnoreCase(forecastMode) ? "UNAVAILABLE"
                : forecast.isFallbackUsed() ? "DEGRADED" : "SUCCESS";
        boolean degraded = !failures.isEmpty() || "DEGRADED".equals(forecastStatus)
                || attribution.getOverallConfidence() < 0.35
                || forecast.getOverallConfidence() < 0.25;
        return EngineStatus.builder()
                .fusionStatus(fusionStatus)
                .attributionStatus(failures.containsKey("attribution") ? "FAILED" : attribution.getOverallConfidence() < 0.35 ? "LOW_CONFIDENCE" : "SUCCESS")
                .forecastStatus(forecastStatus)
                .enforcementStatus(failures.containsKey("enforcement") ? "FAILED" : enforcement.getRecommendations() != null && !enforcement.getRecommendations().isEmpty() ? "SUCCESS" : "PARTIAL")
                .advisoryStatus(failures.containsKey("advisory") ? "FAILED" : advisories.getAdvisories() != null && !advisories.getAdvisories().isEmpty() ? "SUCCESS" : "PARTIAL")
                .degradedMode(degraded)
                .failureReasons(new LinkedHashMap<>(failures))
                .build();
    }

    private Map<String, Object> environmentalSignals(CityEnvironmentalContext context) {
        Map<String, Object> aqi = safeMap(context.getAqi());
        Map<String, Object> selected = asStringObjectMap(aqi.get("selected"));
        Map<String, Object> station = safeListObject(aqi.get("stations")).stream()
                .map(this::asStringObjectMap)
                .findFirst()
                .orElse(Map.of());
        Map<String, Object> pollutants = asStringObjectMap(aqi.get("pollutants"));
        Object pollutantRows = aqi.get("pollutantBreakdown");
        if (pollutants.isEmpty()) {
            pollutants = asStringObjectMap(station.get("pollutants"));
            pollutantRows = station.get("pollutants");
        }
        if (pollutantRows instanceof List<?> list) {
            pollutants = pollutantsFromRows(list);
        }
        if (pollutants.isEmpty()) {
            pollutants = Map.of(
                    "aqi", number(aqi.get("currentAqi")),
                    "pm25", number(aqi.get("pm25")),
                    "pm10", number(aqi.get("pm10")),
                    "no2", number(aqi.get("no2")),
                    "so2", number(aqi.get("so2")),
                    "co", number(aqi.get("co")),
                    "o3", number(aqi.get("o3"))
            );
        }
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("coordinates", context.getCoordinates() != null ? context.getCoordinates() : Map.of());
        signals.put("weather", context.getWeather() != null ? context.getWeather() : Map.of());
        signals.put("wind", context.getWind() != null ? context.getWind() : Map.of());
        signals.put("pollutants", pollutants);
        signals.put("pollutantBreakdown", pollutantRows instanceof List<?> list ? list : List.of());
        signals.put("selectedAqi", selected);
        signals.put("canonicalAqi", aqi.get("canonicalAqi"));
        signals.put("aqiStandard", valueOrDefault(String.valueOf(aqi.getOrDefault("aqiStandard", "")), "AQI standard unavailable"));
        signals.put("standard", valueOrDefault(String.valueOf(aqi.getOrDefault("standard", selected.getOrDefault("standard", ""))), ""));
        signals.put("aqiCategory", valueOrDefault(String.valueOf(aqi.getOrDefault("aqiCategory", "")), "UNAVAILABLE"));
        signals.put("aqiSourceType", valueOrDefault(String.valueOf(aqi.getOrDefault("sourceType", "")), "UNAVAILABLE"));
        signals.put("aqiSourceLabel", valueOrDefault(String.valueOf(aqi.getOrDefault("sourceLabel", "")), ""));
        signals.put("aqiProvider", valueOrDefault(String.valueOf(aqi.getOrDefault("provider", "")), "unavailable"));
        signals.put("stationName", valueOrDefault(String.valueOf(aqi.getOrDefault("stationName", "")), ""));
        signals.put("stationLatitude", aqi.get("stationLatitude"));
        signals.put("stationLongitude", aqi.get("stationLongitude"));
        signals.put("stationDistanceKm", aqi.get("distanceKm"));
        signals.put("observedAt", valueOrDefault(String.valueOf(aqi.getOrDefault("observedAt", "")), ""));
        signals.put("fetchedAt", valueOrDefault(String.valueOf(aqi.getOrDefault("fetchedAt", "")), ""));
        signals.put("freshnessStatus", valueOrDefault(String.valueOf(aqi.getOrDefault("freshnessStatus", "")), "UNAVAILABLE"));
        signals.put("prominentPollutant", valueOrDefault(String.valueOf(aqi.getOrDefault("prominentPollutant", "")), ""));
        signals.put("primaryPollutant", valueOrDefault(String.valueOf(aqi.getOrDefault("primaryPollutant", selected.getOrDefault("primaryPollutant", ""))), ""));
        signals.put("aqiCalculation", aqi.getOrDefault("calculation", Map.of()));
        signals.put("openWeatherAqiIndex", aqi.get("openWeatherAqiIndex"));
        signals.put("openWeatherAqiScale", valueOrDefault(String.valueOf(aqi.getOrDefault("openWeatherAqiScale", "")), "OPENWEATHER_1_TO_5"));
        signals.put("openWeatherAqiCategory", valueOrDefault(String.valueOf(aqi.getOrDefault("openWeatherAqiCategory", "")), "Unavailable"));
        signals.put("openWeatherEvidence", aqi.getOrDefault("openWeatherEvidence", Map.of()));
        signals.put("calculatedAqi", aqi.get("calculatedAqi"));
        signals.put("lastUpdated", valueOrDefault(String.valueOf(aqi.getOrDefault("lastUpdated", aqi.getOrDefault("latestTimestamp", ""))), ""));
        signals.put("providerTimestamp", valueOrDefault(String.valueOf(aqi.getOrDefault("latestTimestamp", "")), ""));
        signals.put("unavailableReason", valueOrDefault(String.valueOf(aqi.getOrDefault("reason", "")), ""));
        signals.put("fallbackUsed", Boolean.TRUE.equals(aqi.get("fallbackUsed")));
        signals.put("isFallback", Boolean.TRUE.equals(aqi.get("isFallback")));
        signals.put("fallbackReason", valueOrDefault(String.valueOf(aqi.getOrDefault("fallbackReason", "")), ""));
        signals.put("selectionReason", valueOrDefault(String.valueOf(aqi.getOrDefault("selectionReason", "")), ""));
        signals.put("providerReturnedCity", valueOrDefault(String.valueOf(aqi.getOrDefault("providerReturnedCity", "")), ""));
        signals.put("providerReturnedStation", valueOrDefault(String.valueOf(aqi.getOrDefault("providerReturnedStation", "")), ""));
        signals.put("cacheStatus", context.getMetadata() != null && Boolean.TRUE.equals(context.getMetadata().get("aqiCached")) ? "HIT" : "MISS");
        signals.put("httpStatus", aqi.get("httpStatus"));
        String aqiStatus = context.getProviderStatus() != null ? context.getProviderStatus().getOrDefault("aqi", "") : "";
        signals.put("aqiAvailable", Boolean.TRUE.equals(aqi.get("available")) || number(aqi.get("currentAqi")) > 0
                || (number(aqi.get("stationCount")) > 0 && ("SUCCESS".equals(aqiStatus) || "PARTIAL".equals(aqiStatus))));
        signals.put("aqiDataSource", valueOrDefault(String.valueOf(aqi.getOrDefault("dataSource", "")), "unavailable"));
        signals.put("primaryAqiProvider", valueOrDefault(String.valueOf(aqi.getOrDefault("provider", "")), "UNAVAILABLE"));
        signals.put("cpcbPollutantsSource", valueOrDefault(String.valueOf(aqi.getOrDefault("pollutantsSource", "")), ""));
        signals.put("cpcbEvidence", aqi.getOrDefault("cpcbEvidence", Map.of()));
        signals.put("iqAirEvidence", aqi.getOrDefault("iqAirEvidence", Map.of()));
        signals.put("historicalAQI", context.getHistoricalAQI() != null ? context.getHistoricalAQI() : List.of());
        signals.put("providerStatus", context.getProviderStatus() != null ? context.getProviderStatus() : Map.of());
        signals.put("citySummary", aqi.getOrDefault("citySummary", Map.of()));
        signals.put("sourceScope", valueOrDefault(String.valueOf(aqi.getOrDefault("sourceScope", "")), ""));
        return signals;
    }

    private Map<String, ModuleStatus> moduleStatuses(CityEnvironmentalContext context, AttributionResult attribution,
                                                     ForecastResult forecast, EnforcementResult enforcement,
                                                     HealthAdvisoryResult advisories, EngineStatus engineStatus,
                                                     Map<String, String> failures, Integer currentAqi,
                                                     Integer forecastPeak, double overallConfidence,
                                                     String snapshotId, Instant generatedAt,
                                                     Map<String, Object> signals) {
        Map<String, ModuleStatus> statuses = new LinkedHashMap<>();
        statuses.put("currentAqi", status(
                currentAqi != null && currentAqi > 0 ? "AVAILABLE" : "UNAVAILABLE",
                currentAqi != null && currentAqi > 0 ? "OBSERVED_REAL_DATA" : "UNAVAILABLE",
                providerConfidence(context, "aqi", currentAqi != null && currentAqi > 0 ? 0.65 : 0.0),
                currentAqi != null && currentAqi > 0 ? "Canonical AQI selected from provider/fusion context." : valueOrDefault(text(signals.get("unavailableReason")), "No valid provider AQI was returned for this snapshot."),
                currentAqi != null && currentAqi > 0 ? List.of() : List.of("AQI may remain unavailable until CPCB/IQAir/OpenWeather returns a valid observation."),
                currentAqi != null && currentAqi > 0 ? List.of() : List.of("currentAqi"),
                snapshotId, generatedAt));
        boolean pollutantsAvailable = hasPollutants(signals.get("pollutants"));
        statuses.put("pollutants", status(
                pollutantsAvailable ? "AVAILABLE" : currentAqi != null && currentAqi > 0 ? "DERIVED" : "UNAVAILABLE",
                pollutantsAvailable ? "OBSERVED_REAL_DATA" : currentAqi != null && currentAqi > 0 ? "DERIVED_FROM_REAL_DATA" : "UNAVAILABLE",
                pollutantsAvailable ? providerConfidence(context, "aqi", 0.62) : currentAqi != null && currentAqi > 0 ? 0.35 : 0.0,
                pollutantsAvailable ? "Pollutant rows or selected-station pollutant values are present." : currentAqi != null && currentAqi > 0 ? "Only aggregate AQI is available; pollutant display is limited to AQI-derived context." : "No pollutant measurements were returned.",
                pollutantsAvailable ? List.of() : List.of("Pollutant-specific concentrations are not inferred without provider evidence."),
                pollutantsAvailable || currentAqi != null && currentAqi > 0 ? List.of() : List.of("pollutants"),
                snapshotId, generatedAt));
        statuses.put("forecast", status(
                forecastPeak != null && forecastPeak > 0 ? forecast.isFallbackUsed() ? "FALLBACK" : "AVAILABLE" : "UNAVAILABLE",
                forecastOrigin(forecast),
                forecast != null ? forecast.getOverallConfidence() : 0.0,
                forecastPeak != null && forecastPeak > 0 ? "Forecast horizons contain valid predicted AQI values." : "No valid 24/48/72 forecast AQI values were returned.",
                forecast != null && forecast.isFallbackUsed() ? List.of("Forecast uses fallback mode; inspect point fallback reasons for per-horizon limitations.") : List.of(),
                forecastPeak != null && forecastPeak > 0 ? List.of() : List.of("forecast.24h", "forecast.48h", "forecast.72h"),
                snapshotId, generatedAt));
        statuses.put("attribution", status(
                attribution.getSources() != null && !attribution.getSources().isEmpty() ? attribution.getOverallConfidence() < 0.35 ? "PARTIAL" : "DERIVED" : "UNAVAILABLE",
                attribution.getSources() != null && !attribution.getSources().isEmpty() ? "DERIVED_FROM_REAL_DATA" : "UNAVAILABLE",
                attribution.getOverallConfidence(),
                valueOrDefault(attribution.getExplanation(), attribution.getSources() != null && !attribution.getSources().isEmpty() ? "Attribution sources returned." : "Attribution did not return source contributions."),
                attribution.getOverallConfidence() < 0.35 ? List.of("Dominant source should be treated as uncertain; UNKNOWN share is retained.") : List.of(),
                attribution.getSources() != null && !attribution.getSources().isEmpty() ? List.of() : List.of("source contributions"),
                snapshotId, generatedAt));
        statuses.put("geospatial", status(
                "PARTIAL",
                "DERIVED_FROM_REAL_DATA",
                overallConfidence,
                "Geospatial endpoint returns required live layers; exact provider geometries may be partial by layer.",
                List.of("Layer-level metadata declares whether geometry is observed, derived, fallback, or unavailable."),
                List.of(),
                snapshotId, generatedAt));
        Map<String, Object> citySummary = asStringObjectMap(signals.get("citySummary"));
        boolean citySummaryAvailable = Boolean.TRUE.equals(citySummary.get("available")) || number(citySummary.get("freshStationCount")) >= 2;
        statuses.put("citySummary", status(
                citySummaryAvailable ? "AVAILABLE" : "PARTIAL",
                citySummaryAvailable ? "DERIVED_FROM_REAL_DATA" : currentAqi != null && currentAqi > 0 ? "OBSERVED_REAL_DATA" : "UNAVAILABLE",
                citySummaryAvailable ? providerConfidence(context, "aqi", 0.62) : currentAqi != null && currentAqi > 0 ? 0.45 : 0.0,
                citySummaryAvailable ? "City summary is derived from multiple fresh same-standard stations." : currentAqi != null && currentAqi > 0 ? "Local location AQI available. City-wide summary requires multiple fresh, same-standard stations." : "No valid local AQI or compatible city station set is available.",
                citySummaryAvailable ? List.of("Summary is limited to compatible fresh stations.") : List.of("A single local/provider AQI is not presented as a city average."),
                citySummaryAvailable ? List.of() : List.of("multiple fresh same-standard stations"),
                snapshotId, generatedAt));
        statuses.put("enforcement", status(
                enforcement.getRecommendations() != null && !enforcement.getRecommendations().isEmpty() ? "AVAILABLE" : "PARTIAL",
                "RULE_BASED_INFERENCE",
                enforcementConfidence(enforcement),
                enforcement.getRecommendations() != null && !enforcement.getRecommendations().isEmpty() ? "Rule-based enforcement recommendations generated from current AQI, forecast, and attribution." : "No enforcement recommendation crossed the rule threshold.",
                enforcement.getRecommendations() != null && !enforcement.getRecommendations().isEmpty() ? List.of("Recommendations are operational guidance, not automated orders.") : List.of("No action is fabricated when rules do not produce a recommendation."),
                List.of(),
                snapshotId, generatedAt));
        statuses.put("advisory", status(
                advisories.getAdvisories() != null && !advisories.getAdvisories().isEmpty() ? "AVAILABLE" : "PARTIAL",
                "RULE_BASED_INFERENCE",
                advisoryConfidence(advisories),
                advisories.getAdvisories() != null && !advisories.getAdvisories().isEmpty() ? "Health advisories generated from AQI, forecast, exposure, and sensitive-group context." : "No audience-specific advisory crossed the rule threshold.",
                advisories.getAdvisories() != null && !advisories.getAdvisories().isEmpty() ? List.of("Advice is AQI-guidance based and should not replace medical care.") : List.of("No advisory message is fabricated when inputs are too limited."),
                List.of(),
                snapshotId, generatedAt));
        statuses.put("explainability", status(
                "PARTIAL",
                "DERIVED_FROM_REAL_DATA",
                overallConfidence,
                "Explainability is available from the dedicated endpoint and summary reflects current evidence bundle coverage.",
                List.of("Detailed reasoning steps are resolved on demand through the explainability API."),
                List.of(),
                snapshotId, generatedAt));
        statuses.put("copilot", status(
                "PARTIAL",
                "USER_CONTEXT",
                overallConfidence,
                "Copilot answers are generated on demand from decision, explainability, timeline, and geospatial outputs.",
                List.of("Unsupported or under-evidenced questions return precise limited status instead of fabricated answers."),
                List.of(),
                snapshotId, generatedAt));
        failures.forEach((module, reason) -> statuses.put(module, status("ERROR", "UNAVAILABLE", 0.0,
                reason, List.of("Module threw while generating this decision response."), List.of(module), snapshotId, generatedAt)));
        return statuses;
    }

    private ModuleStatus status(String status, String dataOrigin, double confidence, String reason,
                                List<String> limitations, List<String> missingInputs,
                                String snapshotId, Instant generatedAt) {
        return ModuleStatus.builder()
                .status(status)
                .dataOrigin(dataOrigin)
                .confidence(round(clamp(confidence, 0.0, 0.98)))
                .reason(reason)
                .limitations(limitations)
                .missingInputs(missingInputs)
                .snapshotId(snapshotId)
                .generatedAt(generatedAt)
                .build();
    }

    private boolean hasPollutants(Object value) {
        Map<String, Object> pollutants = asStringObjectMap(value);
        return pollutants.entrySet().stream()
                .anyMatch(entry -> !"aqi".equalsIgnoreCase(entry.getKey()) && number(entry.getValue()) > 0);
    }

    private String forecastOrigin(ForecastResult forecast) {
        if (forecast == null) return "UNAVAILABLE";
        if (forecast.getForecast() == null || forecast.getForecast().isEmpty()) return "UNAVAILABLE";
        String origin = forecast.getForecast().values().stream()
                .map(point -> valueOrDefault(point.getDataOrigin(), valueOrDefault(point.getEngine(), valueOrDefault(point.getMode(), forecast.getEngine()))))
                .filter(value -> value != null && !value.isBlank() && !"UNAVAILABLE".equalsIgnoreCase(value))
                .findFirst()
                .orElse(forecast.isFallbackUsed() ? "PERSISTENCE_FALLBACK" : valueOrDefault(forecast.getMode(), "PROVIDER_FORECAST"));
        return normalizedForecastOrigin(origin, forecast.isFallbackUsed());
    }

    private String normalizedForecastOrigin(String origin, boolean fallbackUsed) {
        String value = origin != null ? origin.toUpperCase(Locale.ROOT) : "";
        if (value.startsWith("OPEN_METEO_PROVIDER_FORECAST")) return "OPEN_METEO_PROVIDER_FORECAST";
        if (value.contains("PERSISTENCE") || fallbackUsed) return "PERSISTENCE_FALLBACK";
        if (value.isBlank() || "UNAVAILABLE".equals(value)) return "UNAVAILABLE";
        return "DERIVED_FROM_REAL_DATA";
    }

    private double providerConfidence(CityEnvironmentalContext context, String provider, double fallback) {
        return context.getProviderConfidence() != null && context.getProviderConfidence().containsKey(provider)
                ? context.getProviderConfidence().get(provider)
                : fallback;
    }

    private SharedDecisionSnapshot sharedSnapshot(CityEnvironmentalContext context, ForecastResult forecast,
                                                  AttributionResult attribution, DecisionRequest request) {
        Map<String, Object> aqi = safeMap(context.getAqi());
        Map<String, Object> selected = asStringObjectMap(aqi.get("selected"));
        Map<String, Object> coordinates = safeMap(context.getCoordinates());
        Map<String, Object> pollutants = asStringObjectMap(aqi.get("pollutants"));
        if (pollutants.isEmpty()) {
            pollutants = asStringObjectMap(selected.get("pollutants"));
        }
        if (pollutants.isEmpty()) {
            Object fallbackPollutants = environmentalSignals(context).get("pollutants");
            pollutants = asStringObjectMap(fallbackPollutants);
        }
        String currentStandard = valueOrDefault(text(first(selected, "standard", "aqiStandard")),
                valueOrDefault(text(first(aqi, "standard", "aqiStandard")), ""));
        return SharedDecisionSnapshot.builder()
                .snapshotId(snapshotId(context, attribution))
                .searchedLocationKey(valueOrDefault(attribution != null ? attribution.getLocationKey() : null, locationHash(context, attribution)))
                .latitude(firstPositive(number(coordinates.get("latitude")), request.getLatitude() != null ? request.getLatitude() : 0.0))
                .longitude(firstPositive(number(coordinates.get("longitude")), request.getLongitude() != null ? request.getLongitude() : 0.0))
                .stationKey(text(first(aqi, "stationKey")))
                .stationLocationKey(valueOrDefault(text(first(aqi, "stationLocationKey")), forecast != null ? forecast.getStationLocationKey() : ""))
                .stationName(valueOrDefault(text(first(aqi, "stationName")), forecast != null ? forecast.getStationName() : ""))
                .currentAqi(canonicalCurrentAqi(context))
                .currentAqiStandard(currentStandard)
                .currentProvider(valueOrDefault(text(first(selected, "provider")), valueOrDefault(text(first(aqi, "provider")), forecast != null ? forecast.getCurrentProvider() : "")))
                .forecastStandard(forecast != null ? forecast.getForecastStandard() : currentStandard)
                .pollutants(new LinkedHashMap<>(pollutants))
                .weather(context.getWeather() != null ? new LinkedHashMap<>(context.getWeather()) : new LinkedHashMap<>())
                .observedAt(first(aqi, "observedAt", "timestamp", "lastUpdated", "latestTimestamp", "fetchedAt"))
                .generatedAt(context.getTimestamp() != null ? context.getTimestamp() : Instant.now())
                .build();
    }

    private double overallConfidence(EvidenceBundle evidence, EngineStatus status) {
        double average = evidence.getConfidenceScores().values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.20);
        if (status.isDegradedMode()) {
            average *= 0.85;
        }
        return round(clamp(average, 0.05, 0.98));
    }

    private List<PriorityAction> dedupe(List<PriorityAction> actions) {
        Map<String, PriorityAction> unique = new LinkedHashMap<>();
        for (PriorityAction action : actions) {
            String key = action.getSourceEngine() + ":" + action.getActionType() + ":" + action.getTargetAudience();
            unique.merge(key, action, (existing, incoming) -> incoming.getPriorityScore() > existing.getPriorityScore() ? incoming : existing);
        }
        return new ArrayList<>(unique.values());
    }

    private int currentAqi(CityEnvironmentalContext context) {
        Integer canonical = canonicalCurrentAqi(context);
        if (canonical != null) return canonical;
        return 0;
    }

    private Integer canonicalCurrentAqi(CityEnvironmentalContext context) {
        Map<String, Object> aqi = safeMap(context.getAqi());
        if (Boolean.FALSE.equals(aqi.get("available"))) {
            return null;
        }
        Map<String, Object> selected = asStringObjectMap(aqi.get("selected"));
        double current = firstPositive(number(selected.get("currentAqi")), number(aqi.get("canonicalAqi")), number(aqi.get("currentAqi")), number(aqi.get("aqi")));
        return current > 0 ? (int) Math.round(current) : null;
    }

    private Integer forecastPeak(ForecastResult forecast) {
        return forecast.getForecast() != null
                ? forecast.getForecast().values().stream()
                        .map(ForecastPoint::getPredictedAqi)
                        .filter(value -> value != null && value > 0)
                        .mapToInt(Integer::intValue)
                        .max()
                        .stream()
                        .boxed()
                        .findFirst()
                        .orElse(null)
                : null;
    }

    private PollutionSourceType source(AttributionResult attribution, EnforcementResult enforcement, HealthAdvisoryResult advisories) {
        if (attribution.getDominantSource() != null) return attribution.getDominantSource();
        if (enforcement.getDominantSource() != null) return parseSource(enforcement.getDominantSource());
        if (advisories.getDominantSource() != null) return parseSource(advisories.getDominantSource());
        return PollutionSourceType.UNKNOWN;
    }

    private PollutionSourceType parseSource(String value) {
        try {
            return PollutionSourceType.valueOf(value);
        } catch (Exception ignored) {
            return PollutionSourceType.UNKNOWN;
        }
    }

    private String riskLevel(int aqi) {
        if (aqi <= 50) return "GOOD";
        if (aqi <= 100) return "NORMAL";
        if (aqi <= 200) return "ELEVATED";
        if (aqi <= 300) return "HIGH";
        if (aqi <= 400) return "SEVERE";
        return "EMERGENCY";
    }

    private AttributionResult emptyAttribution(CityEnvironmentalContext context, DecisionRequest request) {
        return AttributionResult.builder()
                .city(valueOrDefault(context.getCity(), request.getCityId()))
                .cityId(valueOrDefault(context.getCityId(), request.getCityId()))
                .wardId(request.getWardId())
                .timestamp(Instant.now())
                .snapshotId(snapshotId(context, null))
                .locationHash(locationHash(context, null))
                .dominantSource(PollutionSourceType.UNKNOWN)
                .overallConfidence(0.15)
                .explanation("Attribution intelligence unavailable; source risk is uncertain.")
                .build();
    }

    private ForecastResult emptyForecast(CityEnvironmentalContext context, DecisionRequest request) {
        return ForecastResult.builder()
                .city(valueOrDefault(context.getCity(), request.getCityId()))
                .cityId(valueOrDefault(context.getCityId(), request.getCityId()))
                .wardId(request.getWardId())
                .generatedAt(Instant.now())
                .snapshotId(snapshotId(context, null))
                .locationHash(locationHash(context, null))
                .overallConfidence(0.15)
                .overallTrend("unknown")
                .fallbackUsed(true)
                .modelVersion("decision-empty")
                .build();
    }

    private EnforcementResult emptyEnforcement(CityEnvironmentalContext context, DecisionRequest request) {
        int current = currentAqi(context);
        return EnforcementResult.builder()
                .city(valueOrDefault(context.getCity(), request.getCityId()))
                .cityId(valueOrDefault(context.getCityId(), request.getCityId()))
                .wardId(request.getWardId())
                .generatedAt(Instant.now())
                .currentAqi(current > 0 ? current : null)
                .forecastPeakAqi(null)
                .dominantSource(PollutionSourceType.UNKNOWN.name())
                .snapshotId(snapshotId(context, null))
                .locationHash(locationHash(context, null))
                .build();
    }

    private HealthAdvisoryResult emptyAdvisory(CityEnvironmentalContext context, DecisionRequest request) {
        int current = currentAqi(context);
        return HealthAdvisoryResult.builder()
                .city(valueOrDefault(context.getCity(), request.getCityId()))
                .cityId(valueOrDefault(context.getCityId(), request.getCityId()))
                .wardId(request.getWardId())
                .generatedAt(Instant.now())
                .currentAqi(current > 0 ? current : null)
                .forecastPeakAqi(null)
                .dominantSource(PollutionSourceType.UNKNOWN.name())
                .snapshotId(snapshotId(context, null))
                .build();
    }

    private double enforcementConfidence(EnforcementResult enforcement) {
        return enforcement.getRecommendations() != null
                ? enforcement.getRecommendations().stream().mapToDouble(EnforcementRecommendation::getConfidence).average().orElse(0.15)
                : 0.15;
    }

    private double advisoryConfidence(HealthAdvisoryResult advisories) {
        return advisories.getAdvisories() != null
                ? advisories.getAdvisories().stream().mapToDouble(HealthAdvisory::getConfidence).average().orElse(0.15)
                : 0.15;
    }

    private void persistForecastSnapshot(DecisionIntelligenceResult decision) {
        if (forecastSnapshotService == null || decision == null) {
            return;
        }
        try {
            forecastSnapshotService.record(decision);
        } catch (Exception e) {
            log.warn("DecisionIntelligence Forecast Snapshot Failed cityId={} reason={}",
                    decision.getCityId(), e.getMessage());
        }
    }

    private double average(Map<String, Double> values) {
        return values != null && !values.isEmpty()
                ? values.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.25)
                : 0.25;
    }

    private String snapshotId(CityEnvironmentalContext context, AttributionResult attribution) {
        if (attribution != null && attribution.getSnapshotId() != null && !attribution.getSnapshotId().isBlank()) {
            return attribution.getSnapshotId();
        }
        Object snapshotId = context != null && context.getMetadata() != null ? context.getMetadata().get("snapshotId") : null;
        if (snapshotId == null && context != null && context.getAqi() != null) {
            snapshotId = context.getAqi().get("snapshotId");
        }
        return snapshotId != null ? String.valueOf(snapshotId) : "UNAVAILABLE";
    }

    private String locationHash(CityEnvironmentalContext context, AttributionResult attribution) {
        if (attribution != null && attribution.getLocationHash() != null && !attribution.getLocationHash().isBlank()) {
            return attribution.getLocationHash();
        }
        Object locationHash = context != null && context.getMetadata() != null ? context.getMetadata().get("locationHash") : null;
        if (locationHash == null && context != null && context.getAqi() != null) {
            locationHash = context.getAqi().get("locationHash");
        }
        return locationHash != null ? String.valueOf(locationHash) : "loc-unknown";
    }

    private String reason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) return value;
        }
        return 0;
    }

    private Integer firstPositiveInteger(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) return value;
        }
        return null;
    }

    private double firstPositive(double... values) {
        for (double value : values) {
            if (value > 0) return value;
        }
        return 0.0;
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

    private Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private String text(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value != null ? value : Map.of();
    }

    private List<Object> safeListObject(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringObjectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private Map<String, Object> pollutantsFromRows(List<?> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object row : rows) {
            Map<String, Object> map = asStringObjectMap(row);
            String normalized = String.valueOf(map.getOrDefault("normalizedPollutant", map.getOrDefault("pollutant", ""))).toLowerCase(Locale.ROOT)
                    .replace(".", "");
            String key = switch (normalized) {
                case "pm25" -> "pm25";
                case "pm10" -> "pm10";
                case "no2" -> "no2";
                case "so2" -> "so2";
                case "o3", "ozone" -> "o3";
                case "co" -> "co";
                case "nh3" -> "nh3";
                default -> normalized;
            };
            if (!key.isBlank()) {
                result.put(key, map.get("concentration"));
                result.put(key + "SubIndex", map.get("subIndex"));
                result.put(key + "Breakpoint", map.get("breakpointUsed"));
            }
        }
        return result;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
