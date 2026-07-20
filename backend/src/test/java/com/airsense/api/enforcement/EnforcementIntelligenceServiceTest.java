package com.airsense.api.enforcement;

import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.fusion.CityEnvironmentalContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnforcementIntelligenceServiceTest {
    private final EnforcementIntelligenceService service = new EnforcementIntelligenceService(null, null, null);

    @Test
    void industrialDominantSourceReturnsIndustrialInspection() {
        EnforcementResult result = service.recommend(
                context("SURAT", 238)
                        .industries(List.of(Map.of("riskLevel", "HIGH", "complianceStatus", "VIOLATION")))
                        .population(Map.of("population", 7_000_000, "schoolsCount", 4, "hospitalsCount", 2))
                        .build(),
                attribution("SURAT", PollutionSourceType.INDUSTRIAL, 0.84),
                forecast("SURAT", 265, "worsening", 0.78),
                request("SURAT")
        );

        assertThat(result.getRecommendations())
                .extracting(EnforcementRecommendation::getActionType)
                .contains(EnforcementActionType.INDUSTRIAL_INSPECTION);
        assertThat(result.getRecommendations().get(0).getResponsibleAgency()).isIn(
                "Pollution Control Board", "Health Department", "Municipal Corporation");
    }

    @Test
    void constructionDominantSourceReturnsDustControl() {
        EnforcementResult result = service.recommend(
                context("PUNE", 210)
                        .construction(List.of(Map.of("status", "ACTIVE", "dustRiskLevel", "HIGH")))
                        .weather(Map.of("humidity", 30, "windSpeed", 2.4))
                        .build(),
                attribution("PUNE", PollutionSourceType.ROAD_DUST_CONSTRUCTION, 0.80),
                forecast("PUNE", 238, "worsening", 0.74),
                request("PUNE")
        );

        assertThat(result.getRecommendations())
                .extracting(EnforcementRecommendation::getActionType)
                .contains(EnforcementActionType.CONSTRUCTION_DUST_CONTROL);
    }

    @Test
    void trafficDominantSourceReturnsTrafficDiversion() {
        EnforcementResult result = service.recommend(
                context("BENGALURU", 196)
                        .traffic(Map.of("sampleCount", 10, "averageCongestionIndex", 0.88))
                        .build(),
                attribution("BENGALURU", PollutionSourceType.TRAFFIC, 0.79),
                forecast("BENGALURU", 226, "worsening", 0.72),
                request("BENGALURU")
        );

        assertThat(result.getRecommendations())
                .extracting(EnforcementRecommendation::getActionType)
                .contains(EnforcementActionType.TRAFFIC_DIVERSION);
    }

    @Test
    void highForecastWorseningAddsHealthAndSchoolActions() {
        EnforcementResult result = service.recommend(
                context("DELHI", 180)
                        .population(Map.of("population", 18_000_000, "schoolsCount", 12, "hospitalsCount", 8))
                        .wind(Map.of("speed", 1.0, "direction", 270))
                        .weather(Map.of("humidity", 82))
                        .build(),
                attribution("DELHI", PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER, 0.70),
                forecast("DELHI", 310, "worsening", 0.76),
                request("DELHI")
        );

        assertThat(result.getRecommendations())
                .extracting(EnforcementRecommendation::getActionType)
                .contains(
                        EnforcementActionType.HEALTH_DEPARTMENT_ALERT,
                        EnforcementActionType.SCHOOL_OUTDOOR_ACTIVITY_RESTRICTION
                );
        assertThat(result.getRecommendations().get(0).getPriorityScore()).isGreaterThanOrEqualTo(65);
    }

    @Test
    void lowAqiReturnsNoActionRequired() {
        EnforcementResult result = service.recommend(
                context("MYSURU", 72).build(),
                attribution("MYSURU", PollutionSourceType.UNKNOWN, 0.30),
                forecast("MYSURU", 86, "stable", 0.68),
                request("MYSURU")
        );

        assertThat(result.getRecommendations()).hasSize(1);
        assertThat(result.getRecommendations().get(0).getActionType()).isEqualTo(EnforcementActionType.NO_ACTION_REQUIRED);
    }

    @Test
    void missingDataFallsBackToLowConfidenceAdvisoryOrNoAction() {
        EnforcementResult result = service.recommend(
                CityEnvironmentalContext.empty("EMPTY"),
                attribution("EMPTY", PollutionSourceType.UNKNOWN, 0.15),
                ForecastResult.builder().cityId("EMPTY").city("EMPTY").overallConfidence(0.15).forecast(Map.of()).build(),
                request("EMPTY")
        );

        assertThat(result.getRecommendations()).isNotEmpty();
        assertThat(result.getRecommendations().get(0).getActionType())
                .isIn(EnforcementActionType.PUBLIC_ADVISORY, EnforcementActionType.NO_ACTION_REQUIRED);
        assertThat(result.getRecommendations().get(0).getConfidence()).isLessThanOrEqualTo(0.30);
    }

    @Test
    void recommendationsAreSortedByPriorityDescending() {
        EnforcementResult result = service.recommend(
                context("MUMBAI", 230)
                        .traffic(Map.of("sampleCount", 8, "averageCongestionIndex", 0.82))
                        .population(Map.of("population", 12_000_000, "schoolsCount", 6, "hospitalsCount", 3))
                        .greenCover(Map.of("greenCoverIndex", 0.12))
                        .wind(Map.of("speed", 1.4))
                        .build(),
                attribution("MUMBAI", PollutionSourceType.TRAFFIC, 0.82),
                forecast("MUMBAI", 285, "worsening", 0.80),
                request("MUMBAI")
        );

        assertThat(result.getRecommendations()).hasSizeBetween(3, 7);
        for (int i = 1; i < result.getRecommendations().size(); i++) {
            assertThat(result.getRecommendations().get(i - 1).getPriorityScore())
                    .isGreaterThanOrEqualTo(result.getRecommendations().get(i).getPriorityScore());
        }
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
                .providerStatus(Map.of(
                        "aqi", "SUCCESS",
                        "weather", "SUCCESS",
                        "traffic", "SUCCESS",
                        "population", "SUCCESS",
                        "industries", "SUCCESS",
                        "construction", "SUCCESS",
                        "greenCover", "SUCCESS"
                ))
                .providerConfidence(Map.of(
                        "aqi", 0.92,
                        "weather", 0.90,
                        "traffic", 0.76,
                        "population", 0.80,
                        "industries", 0.82,
                        "construction", 0.78,
                        "greenCover", 0.65,
                        "satellite", 0.70
                ));
    }

    private AttributionResult attribution(String cityId, PollutionSourceType type, double confidence) {
        return AttributionResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .dominantSource(type)
                .overallConfidence(confidence)
                .sources(List.of(PollutionSourceContribution.builder()
                        .sourceType(type)
                        .contributionPercent(100)
                        .confidence(confidence)
                        .datasetsUsed(List.of("attribution"))
                        .build()))
                .build();
    }

    private ForecastResult forecast(String cityId, int peakAqi, String trend, double confidence) {
        return ForecastResult.builder()
                .city(cityId)
                .cityId(cityId)
                .wardId("WARD-1")
                .overallConfidence(confidence)
                .forecast(Map.of(
                        "24h", point(peakAqi - 20, trend, confidence),
                        "48h", point(peakAqi - 10, trend, confidence),
                        "72h", point(peakAqi, trend, confidence)
                ))
                .build();
    }

    private ForecastPoint point(int aqi, String trend, double confidence) {
        return ForecastPoint.builder()
                .predictedAqi(aqi)
                .trend(trend)
                .confidence(confidence)
                .meteorologicalInfluence("worsening".equals(trend) ? "ACCUMULATION" : "NEUTRAL")
                .build();
    }

    private EnforcementRequest request(String cityId) {
        return EnforcementRequest.builder()
                .cityId(cityId)
                .wardId("WARD-1")
                .build();
    }
}


