package com.airsense.api.advisory;

import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.enforcement.EnforcementIntelligenceService;
import com.airsense.api.enforcement.EnforcementRecommendation;
import com.airsense.api.enforcement.EnforcementRequest;
import com.airsense.api.enforcement.EnforcementResult;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastRequest;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.forecast.ForecastOrchestrator;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import com.airsense.api.attribution.PollutionAttributionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthAdvisoryService {
    private static final List<AdvisoryTargetGroup> TARGET_GROUPS = List.of(
            AdvisoryTargetGroup.GENERAL_PUBLIC,
            AdvisoryTargetGroup.CHILDREN,
            AdvisoryTargetGroup.ELDERLY,
            AdvisoryTargetGroup.PREGNANT_WOMEN,
            AdvisoryTargetGroup.ASTHMA_COPD_PATIENTS,
            AdvisoryTargetGroup.OUTDOOR_WORKERS,
            AdvisoryTargetGroup.CYCLISTS_RUNNERS,
            AdvisoryTargetGroup.SCHOOLS,
            AdvisoryTargetGroup.HOSPITALS
    );

    private final DataFusionService dataFusionService;
    private final PollutionAttributionService attributionService;
    @Qualifier("hyperlocalForecastOrchestrator")
    private final ForecastOrchestrator forecastOrchestrator;
    @Qualifier("phase24EnforcementIntelligenceService")
    private final EnforcementIntelligenceService enforcementService;
    private final AdvisoryMessageCatalog messageCatalog;

    public HealthAdvisoryResult generate(HealthAdvisoryRequest request) {
        HealthAdvisoryRequest normalized = (request != null ? request : HealthAdvisoryRequest.builder().build()).normalized();
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
                .build(), attribution);
        EnforcementResult enforcement = enforcementService.recommend(context, attribution, forecast, EnforcementRequest.builder()
                .cityId(normalized.getCityId())
                .wardId(normalized.getWardId())
                .latitude(normalized.getLatitude())
                .longitude(normalized.getLongitude())
                .build());
        return generate(context, attribution, forecast, enforcement, normalized);
    }

    public HealthAdvisoryResult generate(CityEnvironmentalContext context, AttributionResult attribution,
                                         ForecastResult forecast, EnforcementResult enforcement,
                                         HealthAdvisoryRequest request) {
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(null);
        HealthAdvisoryRequest safeRequest = (request != null ? request : HealthAdvisoryRequest.builder().build()).normalized();
        AdvisorySignals signals = signals(safeContext, attribution, forecast, enforcement, safeRequest);
        log.info("HealthAdvisory Generating cityId={} wardId={} currentAqi={} forecastPeak={} source={}",
                signals.cityId, signals.wardId, signals.currentAqi, signals.forecastPeakAqi, signals.source);

        List<HealthAdvisory> advisories = TARGET_GROUPS.stream()
                .map(target -> advisoryFor(target, signals))
                .sorted(Comparator
                        .comparingInt((HealthAdvisory advisory) -> severityRank(advisory.getSeverity())).reversed()
                        .thenComparing(advisory -> advisory.getTargetGroup().name()))
                .toList();

        if (advisories.isEmpty()) {
            advisories = List.of(advisoryFor(AdvisoryTargetGroup.GENERAL_PUBLIC, signals));
        }

        log.info("HealthAdvisory Success cityId={} advisories={}", signals.cityId, advisories.size());
        return HealthAdvisoryResult.builder()
                .city(signals.city)
                .cityId(signals.cityId)
                .wardId(signals.wardId)
                .generatedAt(Instant.now())
                .snapshotId(signals.snapshotId)
                .currentAqi(signals.currentAqi > 0 ? signals.currentAqi : null)
                .forecastPeakAqi(signals.forecastPeakAqi)
                .dominantSource(signals.source.name())
                .overallSeverity(signals.baseSeverity)
                .overallExposureRisk(exposureRisk(signals.maxAqi))
                .advisories(advisories)
                .providerStatus(safeContext.getProviderStatus() != null ? safeContext.getProviderStatus() : Map.of())
                .build();
    }

    private HealthAdvisory advisoryFor(AdvisoryTargetGroup targetGroup, AdvisorySignals signals) {
        AdvisorySeverity severity = targetSeverity(targetGroup, signals.baseSeverity, signals.dataAvailable);
        ExposureRisk risk = targetExposureRisk(targetGroup, signals.maxAqi, signals.dataAvailable);
        AdvisoryMessageCatalog.AdvisoryFacts facts = facts(signals, severity);
        Instant generatedAt = Instant.now();
        return HealthAdvisory.builder()
                .advisoryId("HADV-" + UUID.randomUUID())
                .targetGroup(targetGroup)
                .severity(severity)
                .title(messageCatalog.title(targetGroup, severity, signals.improving))
                .message(messageCatalog.message(targetGroup, facts))
                .recommendedActions(unique(messageCatalog.actions(targetGroup, facts)))
                .avoidActivities(unique(messageCatalog.avoidActivities(targetGroup, facts)))
                .exposureRisk(risk)
                .validFrom(generatedAt)
                .validUntil(generatedAt.plusSeconds(validityHours(signals) * 3600L))
                .confidence(round(confidence(signals, targetGroup)))
                .generatedAt(generatedAt)
                .evidence(evidence(signals))
                .build();
    }

    private AdvisoryMessageCatalog.AdvisoryFacts facts(AdvisorySignals signals, AdvisorySeverity severity) {
        return new AdvisoryMessageCatalog.AdvisoryFacts(
                signals.city,
                signals.currentAqi,
                signals.forecastPeakAqi,
                severity,
                signals.source,
                signals.worsening,
                signals.improving,
                signals.weatherTrapping,
                signals.rainImproving,
                signals.heatRisk,
                signals.dataAvailable
        );
    }

    private List<HealthAdvisoryEvidence> evidence(AdvisorySignals signals) {
        List<HealthAdvisoryEvidence> evidence = new ArrayList<>();
        evidence.add(HealthAdvisoryEvidence.builder()
                .dataset("aqi")
                .signal("currentAqi")
                .description(messageCatalog.evidenceDescription("aqi", facts(signals, signals.baseSeverity)))
                .confidence(providerConfidence(signals, "aqi"))
                .build());
        evidence.add(HealthAdvisoryEvidence.builder()
                .dataset("forecast")
                .signal("forecastPeakAqi")
                .description(messageCatalog.evidenceDescription("forecast", facts(signals, signals.baseSeverity)))
                .confidence(signals.forecastConfidence)
                .build());
        evidence.add(HealthAdvisoryEvidence.builder()
                .dataset("attribution")
                .signal("dominantSource")
                .description(messageCatalog.evidenceDescription("attribution", facts(signals, signals.baseSeverity)))
                .confidence(signals.attributionConfidence)
                .build());
        if (signals.weatherPresent) {
            evidence.add(HealthAdvisoryEvidence.builder()
                    .dataset("weather")
                    .signal("dispersion")
                    .description(messageCatalog.evidenceDescription("weather", facts(signals, signals.baseSeverity)))
                    .confidence(providerConfidence(signals, "weather"))
                    .build());
        }
        if (!signals.enforcementActions.isEmpty()) {
            evidence.add(HealthAdvisoryEvidence.builder()
                    .dataset("enforcement")
                    .signal("recommendations")
                    .description(messageCatalog.evidenceDescription("enforcement", facts(signals, signals.baseSeverity)))
                    .confidence(signals.enforcementConfidence)
                    .build());
        }
        return evidence;
    }

    private AdvisorySignals signals(CityEnvironmentalContext context, AttributionResult attribution,
                                    ForecastResult forecast, EnforcementResult enforcement,
                                    HealthAdvisoryRequest request) {
        int currentAqi = currentAqi(context, enforcement);
        ForecastPoint peak = forecastPeak(forecast);
        Integer forecastPeak = firstPositiveInteger(
                enforcement != null ? enforcement.getForecastPeakAqi() : null,
                peak != null ? peak.getPredictedAqi() : null
        );
        int maxAqi = forecastPeak != null ? Math.max(currentAqi, forecastPeak) : currentAqi;
        PollutionSourceType source = source(attribution, enforcement);
        double rainProbability = firstPositive(number(safeMap(context.getWeather()).get("rainProbability")),
                number(safeMap(context.getWeather()).get("probabilityOfPrecipitation")),
                number(safeMap(context.getWeather()).get("pop")));
        double windSpeed = firstPositive(number(safeMap(context.getWind()).get("speed")), number(safeMap(context.getWeather()).get("windSpeed")));
        double humidity = firstPositive(context.getHumidity(), number(safeMap(context.getWeather()).get("humidity")));
        double temperature = firstPositive(context.getTemperature(), number(safeMap(context.getWeather()).get("temperature")));
        boolean worsening = peak != null && "worsening".equalsIgnoreCase(peak.getTrend());
        boolean improving = peak != null && "improving".equalsIgnoreCase(peak.getTrend());
        boolean weatherTrapping = (windSpeed > 0 && windSpeed <= 2.0) || humidity >= 78
                || (peak != null && "ACCUMULATION".equalsIgnoreCase(peak.getMeteorologicalInfluence()));
        boolean rainImproving = rainProbability >= 0.50 && improving;
        boolean dataAvailable = currentAqi > 0 || source != PollutionSourceType.UNKNOWN;
        List<String> enforcementActions = enforcementActions(enforcement);

        return AdvisorySignals.builder()
                .city(valueOrDefault(context.getCity(), request.getCityId()))
                .cityId(valueOrDefault(context.getCityId(), request.getCityId()))
                .wardId(valueOrDefault(request.getWardId(), forecast != null ? forecast.getWardId() : null))
                .snapshotId(snapshotId(context, attribution, forecast, enforcement, request))
                .currentAqi(currentAqi)
                .forecastPeakAqi(forecastPeak)
                .maxAqi(maxAqi)
                .baseSeverity(dataAvailable ? severity(maxAqi) : AdvisorySeverity.MODERATE)
                .source(source)
                .worsening(worsening)
                .improving(improving)
                .weatherTrapping(weatherTrapping)
                .rainImproving(rainImproving)
                .heatRisk(temperature >= 38)
                .dataAvailable(dataAvailable)
                .weatherPresent(!safeMap(context.getWeather()).isEmpty() || !safeMap(context.getWind()).isEmpty())
                .providerConfidence(context.getProviderConfidence() != null ? context.getProviderConfidence() : Map.of())
                .providerCompleteness(providerCompleteness(context))
                .attributionConfidence(attribution != null ? attribution.getOverallConfidence() : 0.15)
                .forecastConfidence(forecastPeak != null && forecast != null ? forecast.getOverallConfidence() : 0.0)
                .enforcementConfidence(enforcementConfidence(enforcement))
                .enforcementActions(enforcementActions)
                .build();
    }

    private int currentAqi(CityEnvironmentalContext context, EnforcementResult enforcement) {
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
        return forecast != null && forecast.getForecast() != null
                ? forecast.getForecast().values().stream()
                        .filter(point -> point.getPredictedAqi() != null && point.getPredictedAqi() > 0)
                        .max(Comparator.comparingInt(ForecastPoint::getPredictedAqi))
                        .orElse(null)
                : null;
    }

    private PollutionSourceType source(AttributionResult attribution, EnforcementResult enforcement) {
        if (attribution != null && attribution.getDominantSource() != null) {
            return attribution.getDominantSource();
        }
        if (enforcement != null && enforcement.getDominantSource() != null) {
            try {
                return PollutionSourceType.valueOf(enforcement.getDominantSource());
            } catch (IllegalArgumentException ignored) {
                return PollutionSourceType.UNKNOWN;
            }
        }
        return PollutionSourceType.UNKNOWN;
    }

    private List<String> enforcementActions(EnforcementResult enforcement) {
        return enforcement != null && enforcement.getRecommendations() != null
                ? enforcement.getRecommendations().stream()
                        .map(EnforcementRecommendation::getActionType)
                        .map(Enum::name)
                        .toList()
                : List.of();
    }

    private double enforcementConfidence(EnforcementResult enforcement) {
        return enforcement != null && enforcement.getRecommendations() != null
                ? enforcement.getRecommendations().stream()
                        .mapToDouble(EnforcementRecommendation::getConfidence)
                        .average()
                        .orElse(0.15)
                : 0.15;
    }

    private AdvisorySeverity severity(int aqi) {
        if (aqi <= 100) return AdvisorySeverity.LOW;
        if (aqi <= 150) return AdvisorySeverity.MODERATE;
        if (aqi <= 200) return AdvisorySeverity.HIGH;
        if (aqi <= 300) return AdvisorySeverity.VERY_HIGH;
        return AdvisorySeverity.SEVERE;
    }

    private AdvisorySeverity targetSeverity(AdvisoryTargetGroup targetGroup, AdvisorySeverity base, boolean dataAvailable) {
        if (!dataAvailable) {
            return AdvisorySeverity.MODERATE;
        }
        int boost = switch (targetGroup) {
            case CHILDREN, ELDERLY, PREGNANT_WOMEN, ASTHMA_COPD_PATIENTS -> 1;
            case SCHOOLS, HOSPITALS -> base.ordinal() >= AdvisorySeverity.HIGH.ordinal() ? 1 : 0;
            default -> 0;
        };
        int index = Math.min(AdvisorySeverity.SEVERE.ordinal(), base.ordinal() + boost);
        return AdvisorySeverity.values()[index];
    }

    private ExposureRisk targetExposureRisk(AdvisoryTargetGroup targetGroup, int aqi, boolean dataAvailable) {
        if (!dataAvailable) return ExposureRisk.LIMITED;
        int adjusted = aqi;
        if (targetGroup == AdvisoryTargetGroup.ASTHMA_COPD_PATIENTS || targetGroup == AdvisoryTargetGroup.ELDERLY
                || targetGroup == AdvisoryTargetGroup.CHILDREN || targetGroup == AdvisoryTargetGroup.PREGNANT_WOMEN) {
            adjusted += 35;
        }
        return exposureRisk(adjusted);
    }

    private ExposureRisk exposureRisk(int aqi) {
        if (aqi <= 50) return ExposureRisk.SAFE;
        if (aqi <= 100) return ExposureRisk.LIMITED;
        if (aqi <= 200) return ExposureRisk.UNHEALTHY;
        if (aqi <= 300) return ExposureRisk.VERY_UNHEALTHY;
        return ExposureRisk.HAZARDOUS;
    }

    private double confidence(AdvisorySignals signals, AdvisoryTargetGroup targetGroup) {
        double confidence = signals.providerCompleteness * 0.25
                + signals.attributionConfidence * 0.25
                + signals.forecastConfidence * 0.25
                + signals.enforcementConfidence * 0.15
                + (signals.dataAvailable ? 0.10 : 0.0);
        if (!signals.dataAvailable) {
            confidence = Math.min(confidence, 0.30);
        }
        if (targetGroup == AdvisoryTargetGroup.GENERAL_PUBLIC && signals.baseSeverity == AdvisorySeverity.LOW) {
            confidence += 0.05;
        }
        return clamp(confidence, 0.05, 0.98);
    }

    private int validityHours(AdvisorySignals signals) {
        if (!signals.dataAvailable) return 6;
        if (signals.baseSeverity.ordinal() >= AdvisorySeverity.VERY_HIGH.ordinal()) return 12;
        return 24;
    }

    private double providerCompleteness(CityEnvironmentalContext context) {
        Map<String, String> status = context.getProviderStatus() != null ? context.getProviderStatus() : Map.of();
        if (status.isEmpty()) return 0.25;
        long usable = status.values().stream()
                .filter(value -> "SUCCESS".equalsIgnoreCase(value) || "PARTIAL".equalsIgnoreCase(value))
                .count();
        return clamp((double) usable / status.size(), 0.0, 1.0);
    }

    private double providerConfidence(AdvisorySignals signals, String provider) {
        return signals.providerConfidence.getOrDefault(provider, 0.40);
    }

    private List<String> unique(List<String> values) {
        Set<String> seen = new LinkedHashSet<>(values);
        return new ArrayList<>(seen);
    }

    private int severityRank(AdvisorySeverity severity) {
        return severity != null ? severity.ordinal() : 0;
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
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

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value != null ? value : Map.of();
    }

    private String snapshotId(CityEnvironmentalContext context, AttributionResult attribution,
                              ForecastResult forecast, EnforcementResult enforcement,
                              HealthAdvisoryRequest request) {
        if (request != null && request.getSnapshotId() != null && !request.getSnapshotId().isBlank()) {
            return request.getSnapshotId();
        }
        if (attribution != null && attribution.getSnapshotId() != null && !attribution.getSnapshotId().isBlank()) {
            return attribution.getSnapshotId();
        }
        if (forecast != null && forecast.getSnapshotId() != null && !forecast.getSnapshotId().isBlank()) {
            return forecast.getSnapshotId();
        }
        if (enforcement != null && enforcement.getSnapshotId() != null && !enforcement.getSnapshotId().isBlank()) {
            return enforcement.getSnapshotId();
        }
        Object snapshotId = context != null && context.getMetadata() != null ? context.getMetadata().get("snapshotId") : null;
        if (snapshotId == null && context != null && context.getAqi() != null) {
            snapshotId = context.getAqi().get("snapshotId");
        }
        return snapshotId != null ? String.valueOf(snapshotId) : null;
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
    private static class AdvisorySignals {
        private String city;
        private String cityId;
        private String wardId;
        private String snapshotId;
        private int currentAqi;
        private Integer forecastPeakAqi;
        private int maxAqi;
        private AdvisorySeverity baseSeverity;
        private PollutionSourceType source;
        private boolean worsening;
        private boolean improving;
        private boolean weatherTrapping;
        private boolean rainImproving;
        private boolean heatRisk;
        private boolean dataAvailable;
        private boolean weatherPresent;
        private double providerCompleteness;
        private double attributionConfidence;
        private double forecastConfidence;
        private double enforcementConfidence;
        private Map<String, Double> providerConfidence;
        private List<String> enforcementActions;
    }
}
