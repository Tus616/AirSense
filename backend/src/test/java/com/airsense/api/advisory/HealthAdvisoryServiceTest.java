package com.airsense.api.advisory;

import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.enforcement.EnforcementActionType;
import com.airsense.api.enforcement.EnforcementRecommendation;
import com.airsense.api.enforcement.EnforcementResult;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.fusion.CityEnvironmentalContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthAdvisoryServiceTest {
    private final HealthAdvisoryService service = new HealthAdvisoryService(
            null,
            null,
            null,
            null,
            new AdvisoryMessageCatalog()
    );

    @Test
    void healthyAqiReturnsPositiveAdvisories() {
        HealthAdvisoryResult result = service.generate(
                context("MYSURU", 48).build(),
                attribution("MYSURU", PollutionSourceType.UNKNOWN, 0.55),
                forecast("MYSURU", 62, "stable", 0.72, "NEUTRAL"),
                enforcement("MYSURU", 48, 62, PollutionSourceType.UNKNOWN, List.of(noAction())),
                request("MYSURU")
        );

        assertThat(result.getAdvisories()).hasSize(9);
        assertThat(result.getOverallSeverity()).isEqualTo(AdvisorySeverity.LOW);
        assertThat(result.getAdvisories().get(result.getAdvisories().size() - 1).getRecommendedActions())
                .anyMatch(action -> action.contains("normal activity"));
    }

    @Test
    void severeAqiEscalatesSensitiveGroups() {
        HealthAdvisoryResult result = service.generate(
                context("DELHI", 330)
                        .weather(Map.of("humidity", 82, "windSpeed", 1.1, "rainProbability", 0.0))
                        .build(),
                attribution("DELHI", PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER, 0.76),
                forecast("DELHI", 365, "worsening", 0.78, "ACCUMULATION"),
                enforcement("DELHI", 330, 365, PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER, List.of(healthAlert())),
                request("DELHI")
        );

        assertThat(result.getOverallSeverity()).isEqualTo(AdvisorySeverity.SEVERE);
        assertThat(result.getAdvisories().get(0).getSeverity()).isEqualTo(AdvisorySeverity.SEVERE);
        assertThat(result.getAdvisories())
                .filteredOn(advisory -> advisory.getTargetGroup() == AdvisoryTargetGroup.ASTHMA_COPD_PATIENTS)
                .singleElement()
                .satisfies(advisory -> assertThat(advisory.getExposureRisk()).isEqualTo(ExposureRisk.HAZARDOUS));
    }

    @Test
    void highTrafficPollutionProducesTrafficSpecificAdvice() {
        HealthAdvisoryResult result = service.generate(
                context("BENGALURU", 188)
                        .traffic(Map.of("averageCongestionIndex", 0.86, "sampleCount", 10))
                        .build(),
                attribution("BENGALURU", PollutionSourceType.TRAFFIC, 0.82),
                forecast("BENGALURU", 222, "worsening", 0.74, "NEUTRAL"),
                enforcement("BENGALURU", 188, 222, PollutionSourceType.TRAFFIC,
                        List.of(action(EnforcementActionType.TRAFFIC_DIVERSION, 0.78))),
                request("BENGALURU")
        );

        assertThat(result.getAdvisories())
                .filteredOn(advisory -> advisory.getTargetGroup() == AdvisoryTargetGroup.CYCLISTS_RUNNERS)
                .singleElement()
                .satisfies(advisory -> assertThat(advisory.getAvoidActivities())
                        .anyMatch(action -> action.contains("peak-hour roadside")));
    }

    @Test
    void industrialPollutionProducesIndustrialSpecificAdvice() {
        HealthAdvisoryResult result = service.generate(
                context("SURAT", 212)
                        .industries(List.of(Map.of("riskLevel", "HIGH")))
                        .build(),
                attribution("SURAT", PollutionSourceType.INDUSTRIAL, 0.84),
                forecast("SURAT", 245, "worsening", 0.76, "NEUTRAL"),
                enforcement("SURAT", 212, 245, PollutionSourceType.INDUSTRIAL,
                        List.of(action(EnforcementActionType.INDUSTRIAL_INSPECTION, 0.82))),
                request("SURAT")
        );

        assertThat(result.getAdvisories())
                .filteredOn(advisory -> advisory.getTargetGroup() == AdvisoryTargetGroup.GENERAL_PUBLIC)
                .singleElement()
                .satisfies(advisory -> assertThat(advisory.getAvoidActivities())
                        .anyMatch(action -> action.contains("industrial corridors")));
    }

    @Test
    void rainImprovingAqiAddsRainWashoutGuidance() {
        HealthAdvisoryResult result = service.generate(
                context("KOCHI", 138)
                        .weather(Map.of("rainProbability", 0.88, "humidity", 80, "temperature", 28))
                        .wind(Map.of("speed", 5.5))
                        .build(),
                attribution("KOCHI", PollutionSourceType.UNKNOWN, 0.50),
                forecast("KOCHI", 102, "improving", 0.70, "WASHOUT"),
                enforcement("KOCHI", 138, 102, PollutionSourceType.UNKNOWN, List.of()),
                request("KOCHI")
        );

        assertThat(result.getAdvisories())
                .filteredOn(advisory -> advisory.getTargetGroup() == AdvisoryTargetGroup.GENERAL_PUBLIC)
                .singleElement()
                .satisfies(advisory -> {
                    assertThat(advisory.getMessage()).contains("rainfall");
                    assertThat(advisory.getRecommendedActions()).anyMatch(action -> action.contains("rainfall clears"));
                });
    }

    @Test
    void missingDataReturnsLowConfidenceAdvisories() {
        HealthAdvisoryResult result = service.generate(
                CityEnvironmentalContext.empty("EMPTY"),
                attribution("EMPTY", PollutionSourceType.UNKNOWN, 0.15),
                ForecastResult.builder().city("EMPTY").cityId("EMPTY").overallConfidence(0.15).forecast(Map.of()).build(),
                EnforcementResult.builder().city("EMPTY").cityId("EMPTY").currentAqi(0).forecastPeakAqi(0).recommendations(List.of()).build(),
                request("EMPTY")
        );

        assertThat(result.getAdvisories()).isNotEmpty();
        assertThat(result.getAdvisories()).allSatisfy(advisory -> {
            assertThat(advisory.getConfidence()).isLessThanOrEqualTo(0.30);
            assertThat(advisory.getMessage()).contains("incomplete");
        });
    }

    @Test
    void forecastWorseningSortsHigherSeverityFirst() {
        HealthAdvisoryResult result = service.generate(
                context("PUNE", 142).build(),
                attribution("PUNE", PollutionSourceType.ROAD_DUST_CONSTRUCTION, 0.80),
                forecast("PUNE", 224, "worsening", 0.76, "NEUTRAL"),
                enforcement("PUNE", 142, 224, PollutionSourceType.ROAD_DUST_CONSTRUCTION,
                        List.of(action(EnforcementActionType.CONSTRUCTION_DUST_CONTROL, 0.78))),
                request("PUNE")
        );

        for (int i = 1; i < result.getAdvisories().size(); i++) {
            assertThat(result.getAdvisories().get(i - 1).getSeverity().ordinal())
                    .isGreaterThanOrEqualTo(result.getAdvisories().get(i).getSeverity().ordinal());
        }
        assertThat(result.getAdvisories().get(0).getSeverity()).isIn(AdvisorySeverity.SEVERE, AdvisorySeverity.VERY_HIGH);
    }

    private CityEnvironmentalContext.CityEnvironmentalContextBuilder context(String cityId, int currentAqi) {
        return CityEnvironmentalContext.builder()
                .city(cityId)
                .cityId(cityId)
                .timestamp(Instant.now())
                .aqi(Map.of(
                        "currentAqi", currentAqi,
                        "stations", List.of(Map.of("pollutants", Map.of("aqi", currentAqi)))
                ))
                .providerStatus(Map.of("aqi", "SUCCESS", "weather", "SUCCESS", "traffic", "SUCCESS"))
                .providerConfidence(Map.of("aqi", 0.92, "weather", 0.88, "traffic", 0.76));
    }

    private AttributionResult attribution(String cityId, PollutionSourceType source, double confidence) {
        return AttributionResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .dominantSource(source)
                .overallConfidence(confidence)
                .sources(List.of(PollutionSourceContribution.builder()
                        .sourceType(source)
                        .contributionPercent(100)
                        .confidence(confidence)
                        .build()))
                .build();
    }

    private ForecastResult forecast(String cityId, int peakAqi, String trend, double confidence, String meteorology) {
        return ForecastResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .overallConfidence(confidence)
                .forecast(Map.of(
                        "24h", point(peakAqi, trend, confidence, meteorology),
                        "48h", point(Math.max(0, peakAqi - 8), trend, confidence, meteorology),
                        "72h", point(Math.max(0, peakAqi - 16), trend, confidence, meteorology)
                ))
                .build();
    }

    private ForecastPoint point(int aqi, String trend, double confidence, String meteorology) {
        return ForecastPoint.builder()
                .predictedAqi(aqi)
                .trend(trend)
                .confidence(confidence)
                .meteorologicalInfluence(meteorology)
                .build();
    }

    private EnforcementResult enforcement(String cityId, int currentAqi, int peakAqi, PollutionSourceType source,
                                          List<EnforcementRecommendation> recommendations) {
        return EnforcementResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .currentAqi(currentAqi)
                .forecastPeakAqi(peakAqi)
                .dominantSource(source.name())
                .recommendations(recommendations)
                .build();
    }

    private EnforcementRecommendation action(EnforcementActionType actionType, double confidence) {
        return EnforcementRecommendation.builder()
                .actionType(actionType)
                .confidence(confidence)
                .build();
    }

    private EnforcementRecommendation healthAlert() {
        return action(EnforcementActionType.HEALTH_DEPARTMENT_ALERT, 0.80);
    }

    private EnforcementRecommendation noAction() {
        return action(EnforcementActionType.NO_ACTION_REQUIRED, 0.70);
    }

    private HealthAdvisoryRequest request(String cityId) {
        return HealthAdvisoryRequest.builder()
                .cityId(cityId)
                .wardId("WARD-1")
                .build();
    }
}


