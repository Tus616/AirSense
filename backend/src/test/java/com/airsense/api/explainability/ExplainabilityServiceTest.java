package com.airsense.api.explainability;

import com.airsense.api.advisory.AdvisorySeverity;
import com.airsense.api.advisory.AdvisoryTargetGroup;
import com.airsense.api.advisory.HealthAdvisory;
import com.airsense.api.advisory.HealthAdvisoryEvidence;
import com.airsense.api.advisory.HealthAdvisoryResult;
import com.airsense.api.attribution.AttributionEvidence;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.DecisionSummary;
import com.airsense.api.decision.EngineStatus;
import com.airsense.api.decision.EvidenceBundle;
import com.airsense.api.decision.PriorityAction;
import com.airsense.api.enforcement.EnforcementActionType;
import com.airsense.api.enforcement.EnforcementEvidence;
import com.airsense.api.enforcement.EnforcementRecommendation;
import com.airsense.api.enforcement.EnforcementResult;
import com.airsense.api.forecast.ForecastExplanation;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.geospatial.GeoSpatialSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainabilityServiceTest {
    private final ExplainabilityService service = new ExplainabilityService();

    @Test
    void highConfidenceDecisionBuildsEvidenceAndReasoning() {
        ExplainabilityResult result = service.explain(decision(false, false, false, false, 0.82));

        assertThat(result.getOverallConfidence()).isGreaterThan(0.75);
        assertThat(result.getEvidence()).isNotEmpty();
        assertThat(result.getReasoning()).extracting(ReasoningStep::getStage).contains("Current AQI", "Forecast", "Attribution", "Recommendation");
        assertThat(result.getExplanation()).contains("Current AQI");
        assertThat(result.getDatasets()).contains("aqi", "weather", "forecast");
    }

    @Test
    void lowConfidenceIsExplicitlyExplained() {
        ExplainabilityResult result = service.explain(decision(false, false, false, false, 0.30));

        assertThat(result.getLimitations()).anyMatch(text -> text.contains("Overall confidence is low"));
        assertThat(result.getOverallConfidence()).isEqualTo(0.30);
    }

    @Test
    void missingSatelliteCreatesLimitation() {
        ExplainabilityResult result = service.explain(decision(true, false, false, false, 0.70));

        assertThat(result.getLimitations()).anyMatch(text -> text.toLowerCase().contains("satellite"));
        assertThat(result.getConfidenceBreakdown().getSatellite()).isLessThan(0.5);
    }

    @Test
    void missingWeatherCreatesLimitation() {
        ExplainabilityResult result = service.explain(decision(false, true, false, false, 0.70));

        assertThat(result.getLimitations()).anyMatch(text -> text.toLowerCase().contains("weather"));
        assertThat(result.getConfidenceBreakdown().getWeather()).isLessThan(0.5);
    }

    @Test
    void forecastFallbackAppearsInReasoningAndEvidence() {
        ExplainabilityResult result = service.explain(decision(false, false, true, false, 0.58));

        assertThat(result.getReasoning()).anyMatch(step -> step.getStage().equals("Forecast Fallback"));
        assertThat(result.getLimitations()).anyMatch(text -> text.contains("Forecast engine used fallback"));
        assertThat(result.getEvidence()).anyMatch(item -> item.getSignal().contains("Fallback reason"));
    }

    @Test
    void openMeteoProviderForecastIsNotExplainedAsLocalModelPrediction() {
        DecisionIntelligenceResult decision = decision(false, false, false, false, 0.74);
        decision.getForecast().setEngine("OPEN_METEO_PROVIDER_FORECAST");
        decision.getForecast().setMode("OPEN_METEO_PROVIDER_FORECAST");
        decision.getForecast().getForecast().values().forEach(point -> {
            point.setEngine("OPEN_METEO_PROVIDER_FORECAST");
            point.setMode("OPEN_METEO_PROVIDER_FORECAST");
        });

        ExplainabilityResult result = service.explain(decision);

        assertThat(result.getReasoning()).anyMatch(step -> step.getStatement().contains("Atmospheric Provider Forecast predicts AQI"));
        assertThat(result.getEvidence()).anyMatch(item -> item.getProvider().equals("Atmospheric Provider Forecast"));
        assertThat(result.getModelExplanations()).anyMatch(model -> model.getModelName().equals("Atmospheric Provider Forecast")
                && model.getRulesFired().contains("provider_forecast_used")
                && !model.getRulesFired().contains("model_prediction_used")
                && model.getExplanation().contains("not a locally trained/promoted ML model"));
        assertThat(result.getLimitations()).contains("Forecast is an atmospheric provider forecast, not a locally trained/promoted model.");
        assertThat(result.getExplanation()).contains("atmospheric provider forecast peak");
    }

    @Test
    void providerFailureIsReported() {
        ExplainabilityResult result = service.explain(decision(false, false, false, true, 0.52));

        assertThat(result.getProviderStatus()).containsEntry("weather", "FAILED");
        assertThat(result.getLimitations()).anyMatch(text -> text.contains("weather provider status = FAILED"));
    }

    @Test
    void degradedModeAddsFailureReasons() {
        ExplainabilityResult result = service.explain(decision(false, false, true, true, 0.42));

        assertThat(result.getReasoning()).anyMatch(step -> step.getStage().equals("Degraded Mode"));
        assertThat(result.getLimitations()).anyMatch(text -> text.contains("Decision response is in degraded mode"));
        assertThat(result.getLimitations()).anyMatch(text -> text.contains("forecast failure"));
    }

    private DecisionIntelligenceResult decision(boolean missingSatellite, boolean missingWeather,
                                                boolean forecastFallback, boolean providerFailure,
                                                double confidence) {
        Map<String, String> providerStatus = new java.util.LinkedHashMap<>();
        providerStatus.put("aqi", "SUCCESS");
        if (!missingWeather) providerStatus.put("weather", providerFailure ? "FAILED" : "SUCCESS");
        if (!missingSatellite) providerStatus.put("satellite", "SUCCESS");
        providerStatus.put("traffic", "SUCCESS");

        return DecisionIntelligenceResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .generatedAt(Instant.now())
                .currentAQI(321)
                .overallConfidence(confidence)
                .summary(DecisionSummary.builder()
                        .whatIsHappening("Current AQI is 321 with severe risk.")
                        .whyIsItHappening("Traffic and low wind are contributing to pollutant buildup.")
                        .whatWillHappenNext("Forecast predicts worsening air quality.")
                        .whatShouldOfficialsDoNow("Traffic diversion and water sprinkling are recommended.")
                        .whatShouldCitizensDoNow("Avoid prolonged outdoor exposure.")
                        .build())
                .evidenceBundle(EvidenceBundle.builder()
                        .datasetsUsed(List.of("aqi", "weather", "forecast", "traffic"))
                        .confidenceScores(Map.of(
                                "forecast", forecastFallback ? 0.38 : confidence,
                                "attribution", confidence,
                                "enforcement", confidence,
                                "advisory", confidence,
                                "fusion", confidence
                        ))
                        .providerStatus(providerStatus)
                        .explanations(List.of("PM10 dominant", "Low wind speed"))
                        .build())
                .forecast(forecast(forecastFallback, confidence))
                .attribution(attribution(confidence))
                .enforcement(enforcement(confidence))
                .advisories(advisory(confidence))
                .priorityActions(List.of(PriorityAction.builder()
                        .sourceEngine("enforcement")
                        .actionType("TRAFFIC_DIVERSION")
                        .message("Divert traffic near hotspot")
                        .confidence(confidence)
                        .priorityScore(85)
                        .build()))
                .engineStatus(EngineStatus.builder()
                        .fusionStatus(providerFailure ? "PARTIAL" : "SUCCESS")
                        .forecastStatus(forecastFallback ? "DEGRADED" : "SUCCESS")
                        .attributionStatus(confidence < 0.35 ? "LOW_CONFIDENCE" : "SUCCESS")
                        .enforcementStatus("SUCCESS")
                        .advisoryStatus("SUCCESS")
                        .degradedMode(forecastFallback || providerFailure || confidence < 0.45)
                        .failureReasons(providerFailure || forecastFallback ? Map.of("forecast", "model unavailable", "weather", "provider failed") : Map.of())
                        .build())
                .geospatialSummary(GeoSpatialSummary.builder()
                        .layerCount(12)
                        .geometrySource("synthetic_grid")
                        .confidence(confidence)
                        .degradedMode(forecastFallback || providerFailure)
                        .build())
                .build();
    }

    private ForecastResult forecast(boolean fallback, double confidence) {
        return ForecastResult.builder()
                .generatedAt(Instant.now())
                .overallConfidence(fallback ? 0.38 : confidence)
                .overallTrend("worsening")
                .fallbackUsed(fallback)
                .forecast(Map.of("24h", ForecastPoint.builder()
                        .horizonHours(24)
                        .predictedAqi(355)
                        .confidence(fallback ? 0.38 : confidence)
                        .explanation(ForecastExplanation.builder()
                                .summary("AQI expected to worsen.")
                                .reasons(List.of("low wind speed", "high congestion"))
                                .dataUsed(List.of("aqi", "weather", "traffic"))
                                .fallbackReason(fallback ? "AI service unavailable" : null)
                                .build())
                        .build()))
                .build();
    }

    private AttributionResult attribution(double confidence) {
        return AttributionResult.builder()
                .dominantSource(PollutionSourceType.TRAFFIC)
                .overallConfidence(confidence)
                .explanation("Traffic contribution dominates current pollution.")
                .sources(List.of(PollutionSourceContribution.builder()
                        .sourceType(PollutionSourceType.TRAFFIC)
                        .contributionPercent(58)
                        .confidence(confidence)
                        .datasetsUsed(List.of("traffic", "aqi", "weather"))
                        .evidence(List.of(AttributionEvidence.builder()
                                .dataset("traffic")
                                .signal("high congestion")
                                .weight(0.58)
                                .message("Traffic congestion is high near hotspot.")
                                .build()))
                        .build()))
                .build();
    }

    private EnforcementResult enforcement(double confidence) {
        return EnforcementResult.builder()
                .currentAqi(321)
                .forecastPeakAqi(355)
                .dominantSource("TRAFFIC")
                .recommendations(List.of(EnforcementRecommendation.builder()
                        .actionType(EnforcementActionType.TRAFFIC_DIVERSION)
                        .responsibleAgency("Traffic Police")
                        .reason("Traffic diversion recommended due to source contribution and worsening forecast.")
                        .priorityScore(86)
                        .confidence(confidence)
                        .datasetsUsed(List.of("traffic", "forecast"))
                        .evidence(List.of(EnforcementEvidence.builder()
                                .dataset("traffic")
                                .signal("congestion")
                                .description("Traffic congestion supports diversion.")
                                .confidence(confidence)
                                .build()))
                        .build()))
                .build();
    }

    private HealthAdvisoryResult advisory(double confidence) {
        return HealthAdvisoryResult.builder()
                .currentAqi(321)
                .forecastPeakAqi(355)
                .dominantSource("TRAFFIC")
                .advisories(List.of(HealthAdvisory.builder()
                        .targetGroup(AdvisoryTargetGroup.GENERAL_PUBLIC)
                        .severity(AdvisorySeverity.SEVERE)
                        .title("Avoid outdoor exposure")
                        .message("AQI and forecast indicate severe respiratory exposure risk.")
                        .confidence(confidence)
                        .evidence(List.of(HealthAdvisoryEvidence.builder()
                                .dataset("forecast")
                                .signal("worsening AQI")
                                .description("Forecast supports public health advisory.")
                                .confidence(confidence)
                                .build()))
                        .build()))
                .build();
    }
}


