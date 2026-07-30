package com.airsense.api.decision;

import com.airsense.api.advisory.AdvisorySeverity;
import com.airsense.api.advisory.AdvisoryTargetGroup;
import com.airsense.api.advisory.ExposureRisk;
import com.airsense.api.advisory.HealthAdvisory;
import com.airsense.api.advisory.HealthAdvisoryRequest;
import com.airsense.api.advisory.HealthAdvisoryResult;
import com.airsense.api.advisory.HealthAdvisoryService;
import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.attribution.PollutionAttributionService;
import com.airsense.api.enforcement.EnforcementActionType;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionIntelligenceServiceTest {

    @Test
    void allEnginesHealthyReturnsUnifiedDecision() {
        DecisionIntelligenceService service = service(
                context("DELHI", 210),
                attribution("DELHI", PollutionSourceType.TRAFFIC, 0.82),
                forecast("DELHI", 245, "worsening", 0.78, false),
                enforcement("DELHI", 210, 245, PollutionSourceType.TRAFFIC, 82),
                advisory("DELHI", 210, 245, PollutionSourceType.TRAFFIC, AdvisorySeverity.VERY_HIGH, 0.80)
        );

        DecisionIntelligenceResult result = service.decide(request("DELHI"));

        assertThat(result.getCityId()).isEqualTo("DELHI");
        assertThat(result.getEngineStatus().isDegradedMode()).isFalse();
        assertThat(result.getPriorityActions()).isNotEmpty();
        assertThat(result.getSummary().getWhatShouldOfficialsDoNow()).contains("Traffic");
    }

    @Test
    void forecastFailureReturnsPartialDegradedResponse() {
        DataFusionService fusion = mock(DataFusionService.class);
        PollutionAttributionService attributionService = mock(PollutionAttributionService.class);
        ForecastOrchestrator forecast = mock(ForecastOrchestrator.class);
        EnforcementIntelligenceService enforcement = mock(EnforcementIntelligenceService.class);
        HealthAdvisoryService advisory = mock(HealthAdvisoryService.class);
        CityEnvironmentalContext context = context("PUNE", 180);
        AttributionResult attribution = attribution("PUNE", PollutionSourceType.ROAD_DUST_CONSTRUCTION, 0.76);

        when(fusion.buildContext(any())).thenReturn(context);
        when(attributionService.attribute(any(CityEnvironmentalContext.class), any(AttributionRequest.class))).thenReturn(attribution);
        when(forecast.forecast(any(CityEnvironmentalContext.class), any(ForecastRequest.class), any(AttributionResult.class))).thenThrow(new IllegalStateException("forecast down"));
        when(enforcement.recommend(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementRequest.class)))
                .thenReturn(enforcement("PUNE", 180, 180, PollutionSourceType.ROAD_DUST_CONSTRUCTION, 55));
        when(advisory.generate(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementResult.class), any(HealthAdvisoryRequest.class)))
                .thenReturn(advisory("PUNE", 180, 180, PollutionSourceType.ROAD_DUST_CONSTRUCTION, AdvisorySeverity.HIGH, 0.62));

        DecisionIntelligenceResult result = new DecisionIntelligenceService(fusion, attributionService, forecast, enforcement, advisory)
                .decide(request("PUNE"));

        assertThat(result.getEngineStatus().isDegradedMode()).isTrue();
        assertThat(result.getEngineStatus().getForecastStatus()).isEqualTo("FAILED");
        assertThat(result.getEngineStatus().getFailureReasons()).containsKey("forecast");
        assertThat(result.getForecast()).isNotNull();
    }

    @Test
    void advisoryReceivesFusedSnapshotIdAndDecisionPreservesIt() {
        DataFusionService fusion = mock(DataFusionService.class);
        PollutionAttributionService attributionService = mock(PollutionAttributionService.class);
        ForecastOrchestrator forecast = mock(ForecastOrchestrator.class);
        EnforcementIntelligenceService enforcement = mock(EnforcementIntelligenceService.class);
        HealthAdvisoryService advisory = mock(HealthAdvisoryService.class);
        CityEnvironmentalContext context = context("LUCKNOW", 88);
        AttributionResult attribution = attribution("LUCKNOW", PollutionSourceType.TRAFFIC, 0.82);
        ForecastResult forecastResult = forecast("LUCKNOW", 90, "stable", 0.72, true);
        EnforcementResult enforcementResult = enforcement("LUCKNOW", 88, 90, PollutionSourceType.TRAFFIC, 70);
        HealthAdvisoryResult advisoryResult = advisory("LUCKNOW", 88, 90, PollutionSourceType.TRAFFIC, AdvisorySeverity.MODERATE, 0.68);

        when(fusion.buildContext(any())).thenReturn(context);
        when(attributionService.attribute(any(CityEnvironmentalContext.class), any(AttributionRequest.class))).thenReturn(attribution);
        when(forecast.forecast(any(CityEnvironmentalContext.class), any(ForecastRequest.class), any(AttributionResult.class))).thenReturn(forecastResult);
        when(enforcement.recommend(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementRequest.class)))
                .thenReturn(enforcementResult);
        when(advisory.generate(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementResult.class), any(HealthAdvisoryRequest.class)))
                .thenReturn(advisoryResult);

        DecisionIntelligenceResult result = new DecisionIntelligenceService(fusion, attributionService, forecast, enforcement, advisory)
                .decide(request("LUCKNOW"));

        ArgumentCaptor<HealthAdvisoryRequest> captor = ArgumentCaptor.forClass(HealthAdvisoryRequest.class);
        verify(advisory).generate(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementResult.class), captor.capture());
        assertThat(captor.getValue().getSnapshotId()).isEqualTo("snap-test-LUCKNOW");
        assertThat(result.getSnapshotId()).isEqualTo("snap-test-LUCKNOW");
    }

    @Test
    void decisionResponseIncludesSharedSnapshotForDependentModules() {
        CityEnvironmentalContext context = context("DELHI", 127);
        ForecastResult forecast = forecast("DELHI", 158, "worsening", 0.62, false);
        forecast.setForecastStandard("US_AQI");
        forecast.setCurrentProvider("IQAIR");
        forecast.setStationLocationKey("in:28.614:77.209");

        DecisionIntelligenceResult result = assemble(
                context,
                attribution("DELHI", PollutionSourceType.REGIONAL_TRANSPORT, 0.62),
                forecast,
                enforcement("DELHI", 127, 158, PollutionSourceType.REGIONAL_TRANSPORT, 61),
                advisory("DELHI", 127, 158, PollutionSourceType.REGIONAL_TRANSPORT, AdvisorySeverity.MODERATE, 0.65)
        );

        assertThat(result.getSharedSnapshot()).isNotNull();
        assertThat(result.getSharedSnapshot().getSnapshotId()).isEqualTo("snap-test-DELHI");
        assertThat(result.getSharedSnapshot().getCurrentAqi()).isEqualTo(127);
        assertThat(result.getSharedSnapshot().getCurrentAqiStandard()).isEqualTo("INDIA_NAQI");
        assertThat(result.getSharedSnapshot().getCurrentProvider()).isEqualTo("CPCB_CAAQMS");
        assertThat(result.getSharedSnapshot().getForecastStandard()).isEqualTo("US_AQI");
        assertThat(result.getSharedSnapshot().getPollutants()).containsKey("aqi");
        assertThat(result.getForecast().getSnapshotId()).isEqualTo(result.getSharedSnapshot().getSnapshotId());
        assertThat(result.getEnforcement().getSnapshotId()).isEqualTo(result.getSharedSnapshot().getSnapshotId());
        assertThat(result.getAdvisories().getSnapshotId()).isEqualTo(result.getSharedSnapshot().getSnapshotId());
        assertThat(result.getModuleStatuses()).containsKeys("currentAqi", "pollutants", "forecast",
                "attribution", "geospatial", "citySummary", "enforcement", "advisory", "explainability", "copilot");
        assertThat(result.getModuleStatuses().get("currentAqi").getDataOrigin()).isEqualTo("OBSERVED_REAL_DATA");
        assertThat(result.getModuleStatuses().get("forecast").getStatus()).isEqualTo("AVAILABLE");
        assertThat(result.getModuleStatuses().values()).allSatisfy(status -> assertThat(status.getDataOrigin())
                .isIn("OBSERVED_REAL_DATA", "DERIVED_FROM_REAL_DATA", "OPEN_METEO_PROVIDER_FORECAST",
                        "PERSISTENCE_FALLBACK", "RULE_BASED_INFERENCE", "USER_CONTEXT", "UNAVAILABLE"));
    }

    @Test
    void attributionLowConfidenceDoesNotDegradeOperationalHealthWhenCoreEvidenceExists() {
        DecisionIntelligenceResult result = assemble(
                context("KOCHI", 132),
                attribution("KOCHI", PollutionSourceType.UNKNOWN, 0.20),
                forecast("KOCHI", 145, "stable", 0.72, false),
                enforcement("KOCHI", 132, 145, PollutionSourceType.UNKNOWN, 30),
                advisory("KOCHI", 132, 145, PollutionSourceType.UNKNOWN, AdvisorySeverity.MODERATE, 0.55)
        );

        assertThat(result.getEngineStatus().isDegradedMode()).isFalse();
        assertThat(result.getEngineStatus().getAttributionStatus()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void screenshotStateProviderForecastIsOperationalAndActionable() {
        ForecastResult forecast = providerForecast("LUCKNOW", List.of(60, 68, 78), 0.72);
        AttributionResult attribution = screenshotAttribution("LUCKNOW");
        EnforcementResult enforcement = new EnforcementIntelligenceService(null, null, null)
                .recommend(screenshotContext("LUCKNOW", 80), attribution, forecast, EnforcementRequest.builder()
                        .cityId("LUCKNOW")
                        .wardId("WARD-1")
                        .build());

        DecisionIntelligenceResult result = assemble(
                screenshotContext("LUCKNOW", 80),
                attribution,
                forecast,
                enforcement,
                advisory("LUCKNOW", 80, 78, PollutionSourceType.TRAFFIC, AdvisorySeverity.MODERATE, 0.70)
        );

        assertThat(result.getModuleStatuses().get("forecast").getStatus()).isEqualTo("AVAILABLE");
        assertThat(result.getEngineStatus().isDegradedMode()).isFalse();
        assertThat(result.getEngineStatus().getForecastStatus()).isEqualTo("SUCCESS");
        assertThat(result.getEngineStatus().getProviderForecastStatus()).isEqualTo("ONLINE");
        assertThat(result.getEngineStatus().getForecastHorizonCount()).isEqualTo(3);
        assertThat(result.getEngineStatus().getPersistenceFallbackHorizonCount()).isZero();
        assertThat(result.getRiskAssessment().getRiskLevel()).isEqualTo("MODERATE");
        assertThat(result.getRiskAssessment().getOverallRiskLevel()).isEqualTo("MODERATE");
        assertThat(result.getRiskAssessment().getPeakForecastAqi()).isEqualTo(78);
        assertThat(result.getRiskAssessment().getDecisionSummary()).contains("Continue monitoring");
        assertThat(result.getSummary().getWhatWillHappenNext()).doesNotContain("Forecast is unavailable");

        EnforcementRecommendation recommendation = result.getEnforcement().getRecommendations().get(0);
        assertThat(recommendation.getActionType()).isEqualTo(EnforcementActionType.MONITORING);
        assertThat(recommendation.getPriorityLevel()).isEqualTo("LOW");
        assertThat(recommendation.getTitle()).isEqualTo("Continue monitoring");
        assertThat(recommendation.getActionLabel()).isEqualTo("No immediate enforcement required");
        assertThat(recommendation.getAgencyStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(recommendation.getResponsibleAgency()).isBlank();
        assertThat(recommendation.getForecastAvailable()).isTrue();
        assertThat(recommendation.getConfidence()).isEqualTo(0.72);
        assertThat(recommendation.getReason())
                .contains("atmospheric forecast remains in the moderate range")
                .doesNotContain("forecast is unavailable")
                .doesNotContain("required.Forecast");
        assertThat(recommendation.getRecommendedActions())
                .containsExactly("Continue routine monitoring", "Review the next forecast update");

        int totalContribution = result.getAttribution().getSources().stream()
                .mapToInt(PollutionSourceContribution::getContributionPercent)
                .sum();
        assertThat(totalContribution).isEqualTo(100);
    }

    @Test
    void highAqiProducesSevereRiskAndUrgentAlert() {
        DecisionIntelligenceResult result = assemble(
                context("DELHI", 330),
                attribution("DELHI", PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER, 0.78),
                forecast("DELHI", 370, "worsening", 0.80, false),
                enforcement("DELHI", 330, 370, PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER, 92),
                advisory("DELHI", 330, 370, PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER, AdvisorySeverity.SEVERE, 0.82)
        );

        assertThat(result.getRiskAssessment().getOverallRiskLevel()).isEqualTo("SEVERE");
        assertThat(result.getPriorityActions())
                .extracting(PriorityAction::getActionType)
                .contains("URGENT_ALERT");
    }

    @Test
    void lowAqiProducesNormalRisk() {
        DecisionIntelligenceResult result = assemble(
                context("MYSURU", 62),
                attribution("MYSURU", PollutionSourceType.UNKNOWN, 0.55),
                forecast("MYSURU", 82, "stable", 0.72, false),
                enforcement("MYSURU", 62, 82, PollutionSourceType.UNKNOWN, 8),
                advisory("MYSURU", 62, 82, PollutionSourceType.UNKNOWN, AdvisorySeverity.LOW, 0.74)
        );

        assertThat(result.getRiskAssessment().getOverallRiskLevel()).isEqualTo("MODERATE");
        assertThat(result.getPriorityActions())
                .extracting(PriorityAction::getActionType)
                .doesNotContain("URGENT_ALERT");
    }

    @Test
    void multipleEngineFailuresStillReturnPartialDecision() {
        DataFusionService fusion = mock(DataFusionService.class);
        PollutionAttributionService attribution = mock(PollutionAttributionService.class);
        ForecastOrchestrator forecast = mock(ForecastOrchestrator.class);
        EnforcementIntelligenceService enforcement = mock(EnforcementIntelligenceService.class);
        HealthAdvisoryService advisory = mock(HealthAdvisoryService.class);

        when(fusion.buildContext(any())).thenThrow(new IllegalStateException("fusion offline"));
        when(attribution.attribute(any(CityEnvironmentalContext.class), any(AttributionRequest.class))).thenThrow(new IllegalStateException("attribution offline"));
        when(forecast.forecast(any(CityEnvironmentalContext.class), any(ForecastRequest.class), any(AttributionResult.class))).thenThrow(new IllegalStateException("forecast offline"));
        when(enforcement.recommend(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementRequest.class)))
                .thenThrow(new IllegalStateException("enforcement offline"));
        when(advisory.generate(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementResult.class), any(HealthAdvisoryRequest.class)))
                .thenThrow(new IllegalStateException("advisory offline"));

        DecisionIntelligenceResult result = new DecisionIntelligenceService(fusion, attribution, forecast, enforcement, advisory)
                .decide(request("EMPTY"));

        assertThat(result.getEngineStatus().isDegradedMode()).isTrue();
        assertThat(result.getEngineStatus().getFailureReasons()).containsKeys("fusion", "attribution", "forecast", "enforcement", "advisory");
        assertThat(result.getSummary()).isNotNull();
        assertThat(result.getAttribution()).isNotNull();
        assertThat(result.getForecast()).isNotNull();
        assertThat(result.getEnforcement()).isNotNull();
        assertThat(result.getAdvisories()).isNotNull();
    }

    private DecisionIntelligenceResult assemble(CityEnvironmentalContext context, AttributionResult attribution,
                                                ForecastResult forecast, EnforcementResult enforcement,
                                                HealthAdvisoryResult advisory) {
        return new DecisionIntelligenceService(null, null, null, null, null)
                .assemble(context, attribution, forecast, enforcement, advisory, request(context.getCityId()), Map.of());
    }

    private DecisionIntelligenceService service(CityEnvironmentalContext context, AttributionResult attribution,
                                                ForecastResult forecast, EnforcementResult enforcement,
                                                HealthAdvisoryResult advisory) {
        DataFusionService fusion = mock(DataFusionService.class);
        PollutionAttributionService attributionService = mock(PollutionAttributionService.class);
        ForecastOrchestrator forecastService = mock(ForecastOrchestrator.class);
        EnforcementIntelligenceService enforcementService = mock(EnforcementIntelligenceService.class);
        HealthAdvisoryService advisoryService = mock(HealthAdvisoryService.class);

        when(fusion.buildContext(any())).thenReturn(context);
        when(attributionService.attribute(any(CityEnvironmentalContext.class), any(AttributionRequest.class))).thenReturn(attribution);
        when(forecastService.forecast(any(CityEnvironmentalContext.class), any(ForecastRequest.class), any(AttributionResult.class))).thenReturn(forecast);
        when(enforcementService.recommend(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementRequest.class)))
                .thenReturn(enforcement);
        when(advisoryService.generate(any(CityEnvironmentalContext.class), any(AttributionResult.class), any(ForecastResult.class), any(EnforcementResult.class), any(HealthAdvisoryRequest.class)))
                .thenReturn(advisory);
        return new DecisionIntelligenceService(fusion, attributionService, forecastService, enforcementService, advisoryService);
    }

    private CityEnvironmentalContext context(String cityId, int currentAqi) {
        return CityEnvironmentalContext.builder()
                .city(cityId)
                .cityId(cityId)
                .timestamp(Instant.now())
                .aqi(Map.of(
                        "available", true,
                        "currentAqi", currentAqi,
                        "pollutants", Map.of("aqi", currentAqi, "pm25", 58, "pm10", 82),
                        "selected", Map.of("currentAqi", currentAqi, "standard", "INDIA_NAQI", "provider", "CPCB_CAAQMS")
                ))
                .population(Map.of("population", 10_000_000, "schoolsCount", 6, "hospitalsCount", 3))
                .providerStatus(Map.of("aqi", "SUCCESS", "weather", "SUCCESS", "traffic", "SUCCESS"))
                .providerConfidence(Map.of("aqi", 0.92, "weather", 0.88, "traffic", 0.76))
                .metadata(Map.of("snapshotId", "snap-test-" + cityId))
                .build();
    }

    private AttributionResult attribution(String cityId, PollutionSourceType source, double confidence) {
        return AttributionResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .timestamp(Instant.now())
                .snapshotId("snap-test-" + cityId)
                .locationHash("loc-test-" + cityId)
                .dominantSource(source)
                .overallConfidence(confidence)
                .explanation("Dominant source is " + source)
                .sources(List.of(PollutionSourceContribution.builder()
                        .sourceType(source)
                        .contributionPercent(100)
                        .confidence(confidence)
                        .build()))
                .build();
    }

    private ForecastResult forecast(String cityId, int peakAqi, String trend, double confidence, boolean fallback) {
        return ForecastResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .generatedAt(Instant.now())
                .snapshotId("snap-test-" + cityId)
                .locationHash("loc-test-" + cityId)
                .overallConfidence(confidence)
                .overallTrend(trend)
                .fallbackUsed(fallback)
                .mode("TRAINED_MODEL")
                .forecast(Map.of("24h", ForecastPoint.builder()
                        .predictedAqi(peakAqi)
                        .trend(trend)
                        .confidence(confidence)
                        .build()))
                .build();
    }

    private CityEnvironmentalContext screenshotContext(String cityId, int currentAqi) {
        return CityEnvironmentalContext.builder()
                .city(cityId)
                .cityId(cityId)
                .timestamp(Instant.now())
                .aqi(Map.of(
                        "available", true,
                        "currentAqi", currentAqi,
                        "pollutants", Map.of("aqi", currentAqi),
                        "selected", Map.of("currentAqi", currentAqi, "standard", "US_AQI", "provider", "CPCB_CAAQMS")
                ))
                .providerStatus(Map.of("aqi", "SUCCESS", "weather", "SUCCESS", "traffic", "SUCCESS"))
                .providerConfidence(Map.of("aqi", 0.92, "weather", 0.88, "traffic", 0.76))
                .metadata(Map.of("snapshotId", "snap-test-" + cityId))
                .build();
    }

    private ForecastResult providerForecast(String cityId, List<Integer> values, double confidence) {
        return ForecastResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .generatedAt(Instant.now())
                .snapshotId("snap-test-" + cityId)
                .locationHash("loc-test-" + cityId)
                .overallConfidence(confidence)
                .overallTrend("stable to improving")
                .fallbackUsed(false)
                .engine("OPEN_METEO_PROVIDER_FORECAST")
                .mode("OPEN_METEO_PROVIDER_FORECAST")
                .forecastStandard("US_AQI")
                .currentProvider("OPEN_METEO")
                .forecast(Map.of(
                        "24h", providerPoint(24, values.get(0), confidence),
                        "48h", providerPoint(48, values.get(1), confidence),
                        "72h", providerPoint(72, values.get(2), confidence)
                ))
                .build();
    }

    private ForecastPoint providerPoint(int horizon, int aqi, double confidence) {
        return ForecastPoint.builder()
                .horizonHours(horizon)
                .predictedAqi(aqi)
                .trend("stable")
                .confidence(confidence)
                .engine("OPEN_METEO_PROVIDER_FORECAST")
                .mode("OPEN_METEO_PROVIDER_FORECAST")
                .fallbackReason("CHRONOS_DISABLED")
                .fallbackUsed(false)
                .aqiStandard("US_AQI")
                .provider("OPEN_METEO")
                .modelPromotionStatus("NOT_APPLICABLE")
                .build();
    }

    private AttributionResult screenshotAttribution(String cityId) {
        return AttributionResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .timestamp(Instant.now())
                .snapshotId("snap-test-" + cityId)
                .locationHash("loc-test-" + cityId)
                .dominantSource(PollutionSourceType.TRAFFIC)
                .overallConfidence(0.28)
                .explanation("Traffic is one possible contributing source, but source evidence is limited.")
                .sources(List.of(
                        PollutionSourceContribution.builder()
                                .sourceType(PollutionSourceType.UNKNOWN)
                                .displayName("Unknown")
                                .contributionPercent(50)
                                .confidence(0.20)
                                .dataOrigin("DERIVED_FROM_REAL_DATA")
                                .dataAvailability("PARTIAL")
                                .build(),
                        PollutionSourceContribution.builder()
                                .sourceType(PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER)
                                .displayName("Secondary aerosols / other")
                                .contributionPercent(34)
                                .confidence(0.24)
                                .dataOrigin("DERIVED_FROM_REAL_DATA")
                                .dataAvailability("PARTIAL")
                                .build(),
                        PollutionSourceContribution.builder()
                                .sourceType(PollutionSourceType.TRAFFIC)
                                .displayName("Traffic")
                                .contributionPercent(16)
                                .confidence(0.26)
                                .dataOrigin("DERIVED_FROM_REAL_DATA")
                                .dataAvailability("PARTIAL")
                                .build()
                ))
                .build();
    }

    private EnforcementResult enforcement(String cityId, int currentAqi, int peakAqi, PollutionSourceType source, int priority) {
        return EnforcementResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .generatedAt(Instant.now())
                .currentAqi(currentAqi)
                .forecastPeakAqi(peakAqi)
                .dominantSource(source.name())
                .snapshotId("snap-test-" + cityId)
                .locationHash("loc-test-" + cityId)
                .recommendations(List.of(EnforcementRecommendation.builder()
                        .actionType(source == PollutionSourceType.TRAFFIC ? EnforcementActionType.TRAFFIC_DIVERSION : EnforcementActionType.PUBLIC_ADVISORY)
                        .responsibleAgency(source == PollutionSourceType.TRAFFIC ? "Traffic Police" : "Municipal Corporation")
                        .reason(source == PollutionSourceType.TRAFFIC ? "Traffic diversion recommended" : "Public advisory recommended")
                        .urgency(priority >= 80 ? "IMMEDIATE" : "TODAY")
                        .priorityScore(priority)
                        .confidence(0.78)
                        .datasetsUsed(List.of("aqi", "forecast"))
                        .build()))
                .build();
    }

    private HealthAdvisoryResult advisory(String cityId, int currentAqi, int peakAqi, PollutionSourceType source,
                                          AdvisorySeverity severity, double confidence) {
        return HealthAdvisoryResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .generatedAt(Instant.now())
                .currentAqi(currentAqi)
                .forecastPeakAqi(peakAqi)
                .dominantSource(source.name())
                .snapshotId("snap-test-" + cityId)
                .overallSeverity(severity)
                .overallExposureRisk(ExposureRisk.UNHEALTHY)
                .advisories(List.of(HealthAdvisory.builder()
                        .targetGroup(AdvisoryTargetGroup.GENERAL_PUBLIC)
                        .severity(severity)
                        .title("Citizen advisory")
                        .message("Follow AQI precautions")
                        .confidence(confidence)
                        .build()))
                .build();
    }

    private DecisionRequest request(String cityId) {
        return DecisionRequest.builder()
                .cityId(cityId)
                .wardId("WARD-1")
                .build();
    }
}


