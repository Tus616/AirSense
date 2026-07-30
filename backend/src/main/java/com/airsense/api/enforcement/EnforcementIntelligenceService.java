package com.airsense.api.enforcement;

import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastRequest;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import com.airsense.api.forecast.ForecastOrchestrator;
import com.airsense.api.attribution.PollutionAttributionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service("phase24EnforcementIntelligenceService")
@RequiredArgsConstructor
public class EnforcementIntelligenceService {
    private final DataFusionService dataFusionService;
    private final PollutionAttributionService attributionService;
    @Qualifier("hyperlocalForecastOrchestrator")
    private final ForecastOrchestrator forecastOrchestrator;

    public EnforcementResult recommend(EnforcementRequest request) {
        EnforcementRequest normalized = (request != null ? request : EnforcementRequest.builder().build()).normalized();
        CityEnvironmentalContext context = dataFusionService.buildContext(FusionRequest.builder()
                .cityId(normalized.getCityId())
                .placeId(normalized.getPlaceId())
                .cityName(normalized.getCityName())
                .state(normalized.getState())
                .country(normalized.getCountry())
                .latitude(normalized.getLatitude())
                .longitude(normalized.getLongitude())
                .build());
        AttributionResult attribution = attributionService.attribute(context, AttributionRequest.builder()
                .cityId(normalized.getCityId())
                .placeId(normalized.getPlaceId())
                .cityName(normalized.getCityName())
                .state(normalized.getState())
                .country(normalized.getCountry())
                .wardId(normalized.getWardId())
                .latitude(normalized.getLatitude())
                .longitude(normalized.getLongitude())
                .build());
        ForecastResult forecast = forecastOrchestrator.forecast(context, ForecastRequest.builder()
                .cityId(normalized.getCityId())
                .wardId(normalized.getWardId())
                .latitude(normalized.getLatitude())
                .longitude(normalized.getLongitude())
                .build());
        return recommend(context, attribution, forecast, normalized);
    }

    public EnforcementResult recommend(CityEnvironmentalContext context, AttributionResult attribution,
                                       ForecastResult forecast, EnforcementRequest request) {
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(null);
        EnforcementRequest safeRequest = (request != null ? request : EnforcementRequest.builder().build()).normalized();
        AttributionResult safeAttribution = attribution != null ? attribution : emptyAttribution(safeContext, safeRequest);
        ForecastResult safeForecast = forecast != null ? forecast : emptyForecast(safeContext, safeRequest);
        EnforcementSignals signals = signals(safeContext, safeAttribution, safeForecast);

        log.info("Enforcement Scoring cityId={} wardId={} currentAqi={} forecastPeak={} source={}",
                signals.cityId, signals.wardId, signals.currentAqi, signals.forecastPeakAqi, signals.dominantSource);

        Map<EnforcementActionType, Candidate> candidates = new LinkedHashMap<>();
        if (signals.currentAqi <= 100 && !forecastExceeds(signals, 100) && signals.dataAvailable) {
            addNoAction(candidates, signals, monitoringReason(signals));
        } else {
            addSourceActions(candidates, signals);
            addRiskActions(candidates, signals);
            addEnvironmentalActions(candidates, signals);
        }

        if (candidates.isEmpty()) {
            if (!signals.dataAvailable) {
                addPublicAdvisory(candidates, signals, 34, "Insufficient intelligence data; issue cautious public advisory");
            } else {
                addNoAction(candidates, signals, "No enforcement action required from current intelligence");
            }
        }

        if ((signals.currentAqi > 150 || forecastExceeds(signals, 180)) && candidates.isEmpty()) {
            addPublicAdvisory(candidates, signals, 55, "Bad AQI requires public communication even without a clear source");
        }

        List<EnforcementRecommendation> recommendations = candidates.values().stream()
                .map(candidate -> toRecommendation(candidate, signals))
                .sorted(Comparator.comparingInt(EnforcementRecommendation::getPriorityScore).reversed())
                .limit(7)
                .toList();
        if (recommendations.size() > 3) {
            recommendations = recommendations.stream().limit(7).toList();
        }

        log.info("Enforcement Success cityId={} recommendations={}", signals.cityId, recommendations.size());
        return EnforcementResult.builder()
                .city(signals.city)
                .cityId(signals.cityId)
                .wardId(signals.wardId)
                .generatedAt(Instant.now())
                .currentAqi(signals.currentAqi > 0 ? signals.currentAqi : null)
                .forecastPeakAqi(signals.forecastPeakAqi)
                .dominantSource(signals.dominantSource.name())
                .snapshotId(safeAttribution.getSnapshotId())
                .locationHash(safeAttribution.getLocationHash())
                .locationKey(safeAttribution.getLocationKey())
                .snapshotObservedAt(safeAttribution.getSnapshotObservedAt())
                .snapshotGeneratedAt(safeAttribution.getSnapshotGeneratedAt())
                .snapshotReused(safeAttribution.getSnapshotReused())
                .recommendations(recommendations)
                .providerStatus(safeContext.getProviderStatus() != null ? safeContext.getProviderStatus() : Map.of())
                .build();
    }

    private void addSourceActions(Map<EnforcementActionType, Candidate> candidates, EnforcementSignals signals) {
        switch (signals.dominantSource) {
            case INDUSTRIAL -> add(candidates, candidate(EnforcementActionType.INDUSTRIAL_INSPECTION,
                    "Pollution Control Board",
                    "Inspect high-risk industrial units and verify stack/control-device compliance",
                    25, "Industrial source attribution is dominant")
                    .evidence("attribution", "dominantSource", "Dominant pollution source is industrial", signals.attributionConfidence)
                    .evidence("industries", "count", "Industrial facilities are present in the fused city context", providerConfidence(signals, "industries")));
            case ROAD_DUST_CONSTRUCTION -> {
                add(candidates, candidate(EnforcementActionType.CONSTRUCTION_DUST_CONTROL,
                    "Municipal Corporation",
                    "Enforce dust screens, on-site water spraying, wheel washing, and debris covering",
                    24, "Construction dust controls are needed")
                    .evidence("attribution", "dominantSource", "Road dust / construction estimate is dominant", signals.attributionConfidence)
                    .evidence("construction", "activeSites", "Construction activity exists in the fused context", providerConfidence(signals, "construction")));
                add(candidates, candidate(EnforcementActionType.ROAD_DUST_WATER_SPRINKLING,
                        "Municipal Corporation",
                        "Deploy mechanized sweeping and water sprinkling on priority corridors",
                        21, "Road dust is a likely contributor")
                        .evidence("attribution", "dominantSource", "Road dust / construction estimate is dominant", signals.attributionConfidence)
                        .evidence("weather", "dryness", "Dry or stagnant conditions can resuspend dust", providerConfidence(signals, "weather")));
            }
            case TRAFFIC -> add(candidates, candidate(EnforcementActionType.TRAFFIC_DIVERSION,
                    "Traffic Police",
                    "Review heavy-vehicle routing and controls near mapped road corridors",
                    23, "Traffic attribution is based on road-infrastructure proxy evidence")
                    .evidence("attribution", "dominantSource", "Dominant pollution source is traffic", signals.attributionConfidence)
                    .evidence("openstreetmap", "roadDensity", "OSM road density is an infrastructure proxy, not live traffic", providerConfidence(signals, "traffic")));
            case BIOMASS_WASTE_BURNING -> add(candidates, candidate(EnforcementActionType.WASTE_BURNING_INSPECTION,
                    "Municipal Corporation",
                    "Inspect waste burning hotspots and issue immediate stop-work or penalty notices",
                    24, "Burning signal requires local inspection")
                    .evidence("attribution", "dominantSource", "Dominant pollution source is biomass or waste burning", signals.attributionConfidence)
                    .evidence("satellite", "thermalAnomaly", "Satellite or pollutant pattern supports burning inspection", providerConfidence(signals, "satellite")));
            default -> {
                if (signals.currentAqi > 150 || forecastExceeds(signals, 180)) {
                    addPublicAdvisory(candidates, signals, 18, "AQI is bad but source attribution is uncertain");
                }
            }
        }
    }

    private void addRiskActions(Map<EnforcementActionType, Candidate> candidates, EnforcementSignals signals) {
        if (forecastExceeds(signals, 200) || (signals.forecastTrendWorsening && forecastExceeds(signals, 150))) {
            add(candidates, candidate(EnforcementActionType.HEALTH_DEPARTMENT_ALERT,
                    "Health Department",
                    "Prepare advisories for clinics, hospitals, and vulnerable citizens",
                    22, "Forecast indicates elevated health risk")
                    .evidence("forecast", "peakAqi", "Forecast peak AQI crosses health action threshold", signals.forecastConfidence));
        }
        if ((signals.currentAqi >= 180 || forecastExceeds(signals, 200)) && signals.schoolsCount > 0) {
            add(candidates, candidate(EnforcementActionType.SCHOOL_OUTDOOR_ACTIVITY_RESTRICTION,
                    "Education Department",
                    "Restrict outdoor assemblies and sports in schools during the forecast risk window",
                    18, "Sensitive school zones need exposure reduction")
                    .evidence("population", "schoolsCount", "Schools are present in the affected context", providerConfidence(signals, "population"))
                    .evidence("forecast", "peakAqi", "Forecast AQI is high enough to affect outdoor activity", signals.forecastConfidence));
        }
        if (signals.currentAqi > 150 || forecastExceeds(signals, 180)) {
            addPublicAdvisory(candidates, signals, 16, "Residents need timely risk communication");
        }
    }

    private void addEnvironmentalActions(Map<EnforcementActionType, Candidate> candidates, EnforcementSignals signals) {
        if (signals.greenCoverIndex > 0 && signals.greenCoverIndex < 0.25 && forecastExceeds(signals, 150)) {
            add(candidates, candidate(EnforcementActionType.GREEN_BUFFER_ACTION,
                    "Urban Forestry Department",
                    "Prioritize green buffer maintenance and dust-capture planting near hotspots",
                    10, "Low green cover reduces local pollution buffering")
                    .evidence("greenCover", "greenCoverIndex", "Green cover index is low", providerConfidence(signals, "greenCover")));
        }
        if (signals.weatherTrappingRisk && forecastExceeds(signals, 150)) {
            add(candidates, candidate(EnforcementActionType.PUBLIC_ADVISORY,
                    "Municipal Corporation",
                    "Issue stagnant-weather advisory and discourage open burning or dust-generating work",
                    15, "Weather trapping can amplify local emissions")
                    .evidence("weather", "windSpeed", "Low wind speed or high humidity increases accumulation risk", providerConfidence(signals, "weather")));
        }
    }

    private void addPublicAdvisory(Map<EnforcementActionType, Candidate> candidates, EnforcementSignals signals, int baseScore, String reason) {
        add(candidates, candidate(EnforcementActionType.PUBLIC_ADVISORY,
                "Municipal Corporation",
                "Issue public AQI advisory with source-specific precautions",
                baseScore, reason)
                .evidence("aqi", "currentAqi", "Current or forecast AQI requires public communication", providerConfidence(signals, "aqi")));
    }

    private void addNoAction(Map<EnforcementActionType, Candidate> candidates, EnforcementSignals signals, String reason) {
        add(candidates, candidate(EnforcementActionType.MONITORING,
                "",
                "Continue routine monitoring",
                5, reason)
                .evidence("aqi", "currentAqi", "AQI remains within acceptable range for enforcement escalation", providerConfidence(signals, "aqi")));
    }

    private String monitoringReason(EnforcementSignals signals) {
        if (signals.forecastPeakAqi != null && signals.forecastPeakAqi > 0) {
            return "Current AQI remains below intervention thresholds, and the atmospheric forecast remains in the moderate range. Continue routine monitoring; no immediate enforcement action is required.";
        }
        return "Current AQI remains below intervention thresholds. Continue routine monitoring; no immediate enforcement action is required.";
    }

    private Candidate candidate(EnforcementActionType type, String agency, String action, int baseScore, String reason) {
        return Candidate.builder()
                .actionType(type)
                .responsibleAgency(agency)
                .action(action)
                .baseScore(baseScore)
                .reason(reason)
                .build();
    }

    private void add(Map<EnforcementActionType, Candidate> candidates, Candidate candidate) {
        candidates.merge(candidate.actionType, candidate, (existing, incoming) -> {
            if (incoming.baseScore > existing.baseScore) {
                incoming.evidence.addAll(existing.evidence);
                return incoming;
            }
            existing.evidence.addAll(incoming.evidence);
            return existing;
        });
    }

    private EnforcementRecommendation toRecommendation(Candidate candidate, EnforcementSignals signals) {
        int priority = priority(candidate, signals);
        double confidence = confidence(candidate, signals);
        List<String> datasets = candidate.evidence.stream()
                .map(EnforcementEvidence::getDataset)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        return EnforcementRecommendation.builder()
                .recommendationId("ENF-" + UUID.randomUUID())
                .city(signals.city)
                .wardId(signals.wardId)
                .priority(priority)
                .priorityScore(priority)
                .priorityLevel(priorityLevel(priority))
                .actionType(candidate.actionType)
                .title(title(candidate))
                .actionLabel(actionLabel(candidate.actionType))
                .responsibleAgency(candidate.responsibleAgency)
                .agencyStatus(candidate.actionType == EnforcementActionType.MONITORING ? "NOT_REQUIRED" : valueOrDefault(candidate.responsibleAgency, "").isBlank() ? "PENDING" : "ASSIGNED")
                .targetArea(valueOrDefault(signals.wardId, signals.cityId))
                .location(signals.location)
                .reason(sentence(candidate.reason) + " " + sentence(candidate.action))
                .recommendedActions(recommendedActions(candidate))
                .evidence(candidate.evidence)
                .supportingEvidence(candidate.evidence)
                .datasetsUsed(datasets)
                .expectedImpact(expectedImpact(candidate.actionType, priority, signals))
                .urgency(urgency(priority, signals))
                .actionWindow(candidate.actionType == EnforcementActionType.MONITORING ? "Next 24 hours" : actionWindow(priority, signals))
                .confidence(round(confidence))
                .limitations(limitations(signals, candidate))
                .forecastEngine(signals.forecastEngine)
                .forecastAvailable(signals.forecastPeakAqi != null && signals.forecastPeakAqi > 0)
                .snapshotId(signals.snapshotId)
                .generatedAt(Instant.now())
                .build();
    }

    private String title(Candidate candidate) {
        if (candidate.actionType == EnforcementActionType.MONITORING || candidate.actionType == EnforcementActionType.NO_ACTION_REQUIRED) {
            return "Continue monitoring";
        }
        return actionLabel(candidate.actionType);
    }

    private String actionLabel(EnforcementActionType type) {
        return switch (type) {
            case MONITORING, NO_ACTION_REQUIRED -> "No immediate enforcement required";
            case INDUSTRIAL_INSPECTION -> "Industrial inspection";
            case CONSTRUCTION_DUST_CONTROL -> "Construction dust control";
            case TRAFFIC_DIVERSION -> "Traffic routing review";
            case ROAD_DUST_WATER_SPRINKLING -> "Road dust suppression";
            case WASTE_BURNING_INSPECTION -> "Waste-burning inspection";
            case SCHOOL_OUTDOOR_ACTIVITY_RESTRICTION -> "School outdoor activity precautions";
            case HEALTH_DEPARTMENT_ALERT -> "Health department readiness";
            case PUBLIC_ADVISORY -> "Public advisory";
            case GREEN_BUFFER_ACTION -> "Green buffer action";
        };
    }

    private List<String> recommendedActions(Candidate candidate) {
        if (candidate.actionType == EnforcementActionType.MONITORING || candidate.actionType == EnforcementActionType.NO_ACTION_REQUIRED) {
            return List.of("Continue routine monitoring", "Review the next forecast update");
        }
        return List.of(candidate.action);
    }

    private String sentence(String value) {
        String text = valueOrDefault(value, "").trim();
        if (text.isBlank()) return "";
        return text.endsWith(".") || text.endsWith("!") || text.endsWith("?") ? text : text + ".";
    }

    private int priority(Candidate candidate, EnforcementSignals signals) {
        double score = candidate.baseScore;
        score += aqiRisk(signals.currentAqi) * 0.28;
        if (signals.forecastPeakAqi != null) {
            score += aqiRisk(signals.forecastPeakAqi) * 0.25;
        }
        if (signals.forecastTrendWorsening) score += 10;
        if (signals.populationDensity >= 1_000_000 || signals.populationDensity >= 10000) score += 8;
        if (signals.schoolsCount > 0 || signals.hospitalsCount > 0) score += 7;
        if (signals.weatherTrappingRisk) score += 8;
        score += signals.attributionConfidence * 8;
        score += signals.forecastConfidence * 8;
        score += signals.providerCompleteness * 6;
        return (int) Math.round(clamp(score, 0, 100));
    }

    private double confidence(Candidate candidate, EnforcementSignals signals) {
        double evidenceConfidence = candidate.evidence.stream()
                .mapToDouble(EnforcementEvidence::getConfidence)
                .average()
                .orElse(0.35);
        double combined = evidenceConfidence * 0.30
                + signals.attributionConfidence * 0.25
                + signals.forecastConfidence * 0.25
                + signals.providerCompleteness * 0.20;
        if (!signals.dataAvailable) {
            combined = Math.min(combined, 0.30);
        }
        return clamp(combined, 0.05, 0.98);
    }

    private ExpectedImpact expectedImpact(EnforcementActionType type, int priority, EnforcementSignals signals) {
        int reduction = switch (type) {
            case INDUSTRIAL_INSPECTION -> 18;
            case CONSTRUCTION_DUST_CONTROL -> 14;
            case TRAFFIC_DIVERSION -> 12;
            case ROAD_DUST_WATER_SPRINKLING -> 10;
            case WASTE_BURNING_INSPECTION -> 16;
            case HEALTH_DEPARTMENT_ALERT, SCHOOL_OUTDOOR_ACTIVITY_RESTRICTION, PUBLIC_ADVISORY, MONITORING -> 0;
            case GREEN_BUFFER_ACTION -> 6;
            case NO_ACTION_REQUIRED -> 0;
        };
        String exposure = switch (type) {
            case HEALTH_DEPARTMENT_ALERT, SCHOOL_OUTDOOR_ACTIVITY_RESTRICTION, PUBLIC_ADVISORY -> "Reduces exposure through behavior change and preparedness";
            case MONITORING, NO_ACTION_REQUIRED -> "No immediate exposure reduction expected";
            default -> "Expected to reduce local emissions if implemented promptly";
        };
        return ExpectedImpact.builder()
                .impactLevel(priority >= 75 ? "HIGH" : priority >= 50 ? "MEDIUM" : "LOW")
                .estimatedAqiReduction(reduction)
                .exposureReduction(exposure)
                .timeframe(type == EnforcementActionType.GREEN_BUFFER_ACTION ? "2-8 weeks" : type == EnforcementActionType.MONITORING ? "Next 24 hours" : "6-24 hours")
                .rationale("Impact estimated from AQI severity, forecast trend, source confidence, and administrative action type")
                .build();
    }

    private EnforcementSignals signals(CityEnvironmentalContext context, AttributionResult attribution, ForecastResult forecast) {
        int currentAqi = currentAqi(context);
        ForecastPoint peak = forecastPeak(forecast);
        Integer forecastPeak = peak != null && peak.getPredictedAqi() != null && peak.getPredictedAqi() > 0 ? peak.getPredictedAqi() : null;
        PollutionSourceType dominantSource = attribution.getDominantSource() != null
                ? attribution.getDominantSource()
                : PollutionSourceType.UNKNOWN;
        double attributionConfidence = attributionConfidence(attribution, dominantSource);
        double forecastConfidence = forecastPeak != null ? forecast.getOverallConfidence() : 0.0;
        Map<String, Object> population = safeMap(context.getPopulation());
        boolean dataAvailable = currentAqi > 0 || forecastPeak != null || dominantSource != PollutionSourceType.UNKNOWN;

        return EnforcementSignals.builder()
                .city(valueOrDefault(context.getCity(), context.getCityId()))
                .cityId(valueOrDefault(context.getCityId(), "DELHI"))
                .wardId(valueOrDefault(attribution.getWardId(), forecast.getWardId()))
                .location(location(context))
                .currentAqi(currentAqi)
                .forecastPeakAqi(forecastPeak)
                .forecastTrendWorsening(peak != null && "worsening".equalsIgnoreCase(peak.getTrend()))
                .forecastConfidence(forecastConfidence)
                .forecastEngine(forecastEngine(forecast, peak))
                .dominantSource(dominantSource)
                .attributionConfidence(attributionConfidence)
                .populationDensity(firstPositive(number(population.get("populationDensity")), number(population.get("density")), number(population.get("population"))))
                .schoolsCount((int) number(population.get("schoolsCount")))
                .hospitalsCount((int) number(population.get("hospitalsCount")))
                .greenCoverIndex(number(safeMap(context.getGreenCover()).get("greenCoverIndex")))
                .weatherTrappingRisk(weatherTrappingRisk(context, peak))
                .providerCompleteness(providerCompleteness(context))
                .providerConfidence(context.getProviderConfidence() != null ? context.getProviderConfidence() : Map.of())
                .dataAvailable(dataAvailable)
                .snapshotId(valueOrDefault(attribution.getSnapshotId(), forecast.getSnapshotId()))
                .build();
    }

    private int currentAqi(CityEnvironmentalContext context) {
        Map<String, Object> aqi = safeMap(context.getAqi());
        if (Boolean.FALSE.equals(aqi.get("available"))) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> selected = aqi.get("selected") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        double current = firstPositive(number(selected.get("currentAqi")), number(aqi.get("currentAqi")), number(aqi.get("canonicalAqi")), number(aqi.get("aqi")));
        if (current > 0) return (int) Math.round(current);
        return 0;
    }

    private ForecastPoint forecastPeak(ForecastResult forecast) {
        return forecast.getForecast() != null
                ? forecast.getForecast().values().stream()
                        .filter(point -> point.getPredictedAqi() != null && point.getPredictedAqi() > 0)
                        .max(Comparator.comparingInt(ForecastPoint::getPredictedAqi))
                        .orElse(null)
                : null;
    }

    private double attributionConfidence(AttributionResult attribution, PollutionSourceType source) {
        return attribution.getSources() != null
                ? attribution.getSources().stream()
                        .filter(contribution -> contribution.getSourceType() == source)
                        .mapToDouble(PollutionSourceContribution::getConfidence)
                        .findFirst()
                        .orElse(attribution.getOverallConfidence())
                : attribution.getOverallConfidence();
    }

    private boolean weatherTrappingRisk(CityEnvironmentalContext context, ForecastPoint peak) {
        double windSpeed = firstPositive(number(safeMap(context.getWind()).get("speed")), number(safeMap(context.getWeather()).get("windSpeed")));
        double humidity = firstPositive(context.getHumidity(), number(safeMap(context.getWeather()).get("humidity")));
        return (windSpeed > 0 && windSpeed <= 2.0)
                || humidity >= 78
                || (peak != null && "ACCUMULATION".equalsIgnoreCase(peak.getMeteorologicalInfluence()));
    }

    private double providerCompleteness(CityEnvironmentalContext context) {
        Map<String, String> status = context.getProviderStatus() != null ? context.getProviderStatus() : Map.of();
        if (status.isEmpty()) return 0.25;
        long usable = status.values().stream()
                .filter(value -> "SUCCESS".equalsIgnoreCase(value) || "PARTIAL".equalsIgnoreCase(value))
                .count();
        return clamp((double) usable / status.size(), 0.0, 1.0);
    }

    private double providerConfidence(EnforcementSignals signals, String provider) {
        return signals.providerConfidence.getOrDefault(provider, 0.40);
    }

    private double aqiRisk(int aqi) {
        if (aqi <= 50) return 5;
        if (aqi <= 100) return 15;
        if (aqi <= 200) return 45;
        if (aqi <= 300) return 70;
        if (aqi <= 400) return 88;
        return 100;
    }

    private String priorityLevel(int priority) {
        if (priority >= 80) return "CRITICAL";
        if (priority >= 65) return "HIGH";
        if (priority >= 40) return "MEDIUM";
        if (priority > 10) return "LOW";
        return "NONE";
    }

    private String urgency(int priority, EnforcementSignals signals) {
        if (priority >= 80 || forecastExceeds(signals, 300)) return "IMMEDIATE";
        if (priority >= 65 || signals.forecastTrendWorsening) return "TODAY";
        if (priority >= 40) return "24_HOURS";
        return "MONITOR";
    }

    private String actionWindow(int priority, EnforcementSignals signals) {
        if (priority >= 80 || forecastExceeds(signals, 300)) return "0-6 hours";
        if (priority >= 65 || signals.forecastTrendWorsening) return "Today";
        if (priority >= 40) return "24 hours";
        return "Monitor over next forecast cycle";
    }

    private List<String> limitations(EnforcementSignals signals, Candidate candidate) {
        List<String> limitations = new ArrayList<>();
        if (!signals.dataAvailable) {
            limitations.add("No current AQI or source attribution was available; recommendation is dependency-status driven.");
        }
        if (signals.attributionConfidence < 0.35) {
            limitations.add("Low attribution confidence; use cautious monitoring or inspection language before punitive action.");
        }
        if ("OPEN_METEO_PROVIDER_FORECAST".equals(signals.forecastEngine)) {
            limitations.add("Forecast input is an atmospheric provider forecast, not a locally promoted CPCB model.");
        }
        if (candidate.actionType == EnforcementActionType.INDUSTRIAL_INSPECTION && signals.dominantSource != PollutionSourceType.INDUSTRIAL) {
            limitations.add("Industrial action requires actual industrial evidence and is not inferred from regional transport.");
        }
        return limitations;
    }

    private String forecastEngine(ForecastResult forecast, ForecastPoint peak) {
        if (peak != null && peak.getEngine() != null && !peak.getEngine().isBlank()) return peak.getEngine();
        if (peak != null && peak.getMode() != null && !peak.getMode().isBlank()) return peak.getMode();
        if (forecast.getEngine() != null && !forecast.getEngine().isBlank()) return forecast.getEngine();
        if (forecast.getMode() != null && !forecast.getMode().isBlank()) return forecast.getMode();
        return valueOrDefault(forecast.getModelVersion(), "UNAVAILABLE");
    }

    private Map<String, Object> location(CityEnvironmentalContext context) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("coordinates", context.getCoordinates() != null ? context.getCoordinates() : Map.of());
        location.put("cityId", context.getCityId());
        return location;
    }

    private boolean forecastExceeds(EnforcementSignals signals, int threshold) {
        return signals.forecastPeakAqi != null && signals.forecastPeakAqi >= threshold;
    }

    private AttributionResult emptyAttribution(CityEnvironmentalContext context, EnforcementRequest request) {
        return AttributionResult.builder()
                .city(context.getCity())
                .cityId(context.getCityId())
                .wardId(request.getWardId())
                .dominantSource(PollutionSourceType.UNKNOWN)
                .overallConfidence(0.15)
                .build();
    }

    private ForecastResult emptyForecast(CityEnvironmentalContext context, EnforcementRequest request) {
        return ForecastResult.builder()
                .city(context.getCity())
                .cityId(context.getCityId())
                .wardId(request.getWardId())
                .overallConfidence(0.15)
                .forecast(Map.of())
                .build();
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    @lombok.Builder
    private static class EnforcementSignals {
        private String city;
        private String cityId;
        private String wardId;
        private Map<String, Object> location;
        private int currentAqi;
        private Integer forecastPeakAqi;
        private boolean forecastTrendWorsening;
        private double forecastConfidence;
        private String forecastEngine;
        private PollutionSourceType dominantSource;
        private double attributionConfidence;
        private double populationDensity;
        private int schoolsCount;
        private int hospitalsCount;
        private double greenCoverIndex;
        private boolean weatherTrappingRisk;
        private double providerCompleteness;
        private Map<String, Double> providerConfidence;
        private boolean dataAvailable;
        private String snapshotId;
    }

    @lombok.Builder
    private static class Candidate {
        private EnforcementActionType actionType;
        private String responsibleAgency;
        private String action;
        private int baseScore;
        private String reason;
        @lombok.Builder.Default
        private List<EnforcementEvidence> evidence = new ArrayList<>();

        private Candidate evidence(String dataset, String signal, String description, double confidence) {
            evidence.add(EnforcementEvidence.builder()
                    .dataset(dataset)
                    .signal(signal)
                    .description(description)
                    .confidence(confidence)
                    .build());
            return this;
        }
    }
}
