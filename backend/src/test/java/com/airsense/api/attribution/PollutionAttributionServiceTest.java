package com.airsense.api.attribution;

import com.airsense.api.fusion.CityEnvironmentalContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PollutionAttributionServiceTest {
    private final PollutionAttributionService service = new PollutionAttributionService(null);

    @Test
    void trafficHeavyContextKeepsRoadDensityAsProxyNotLiveTraffic() {
        CityEnvironmentalContext context = baseContext("BENGALURU")
                .aqi(aqi(188, 72, 96, 68, 8, 0.7, 32))
                .traffic(Map.of("sampleCount", 8, "averageCongestionIndex", 0.86, "averageSpeed", 11.0))
                .wind(Map.of("speed", 3.8, "direction", 270))
                .humidity(44)
                .landUse(Map.of("dominantType", "COMMERCIAL"))
                .build();

        AttributionResult result = service.attribute(context, request("BENGALURU"));

        assertThat(result.getDominantSource()).isEqualTo(PollutionSourceType.TRAFFIC);
        assertThat(result.getSources())
                .extracting(PollutionSourceContribution::getSourceType)
                .contains(PollutionSourceType.TRAFFIC, PollutionSourceType.UNKNOWN);
    }

    @Test
    void constructionContextWithoutGeometryDoesNotForceConstructionDominantSource() {
        CityEnvironmentalContext context = baseContext("PUNE")
                .aqi(aqi(176, 64, 154, 28, 5, 0.4, 22))
                .construction(List.of(Map.of(
                        "permitId", "P-1",
                        "status", "ACTIVE",
                        "dustRiskLevel", "HIGH",
                        "projectType", "ROADWORK"
                )))
                .humidity(28)
                .weather(Map.of("humidity", 28, "temperature", 35))
                .wind(Map.of("speed", 4.5, "direction", 120))
                .greenCover(Map.of("greenCoverIndex", 0.32))
                .build();

        AttributionResult result = service.attribute(context, request("PUNE"));

        assertThat(result.getDominantSource()).isEqualTo(PollutionSourceType.ROAD_DUST_CONSTRUCTION);
        assertThat(result.getSources())
                .flatExtracting(PollutionSourceContribution::getSupportingEvidence)
                .extracting(AttributionEvidence::getType)
                .doesNotContain("CONSTRUCTION_GEOMETRY");
    }

    @Test
    void industrialContextWithoutRealGeometryDoesNotForceIndustrialDominantSource() {
        CityEnvironmentalContext context = baseContext("SURAT")
                .aqi(aqi(205, 82, 110, 34, 31, 2.1, 26))
                .industries(List.of(Map.of(
                        "facilityId", "I-1",
                        "riskLevel", "HIGH",
                        "complianceStatus", "VIOLATION",
                        "category", "CHEMICAL"
                )))
                .landUse(Map.of("dominantType", "INDUSTRIAL"))
                .wind(Map.of("speed", 4.0, "direction", 80))
                .humidity(48)
                .build();

        AttributionResult result = service.attribute(context, request("SURAT"));

        assertThat(result.getDominantSource()).isNotEqualTo(PollutionSourceType.INDUSTRIAL);
        assertThat(result.getSources())
                .extracting(PollutionSourceContribution::getSourceType)
                .contains(PollutionSourceType.UNKNOWN);
    }

    @Test
    void missingDataFallsBackToUnknownWithLowConfidence() {
        CityEnvironmentalContext context = CityEnvironmentalContext.empty("KOCHI");

        AttributionResult result = service.attribute(context, request("KOCHI"));

        assertThat(result.getDominantSource()).isEqualTo(PollutionSourceType.UNKNOWN);
        assertThat(result.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.getSources()).hasSize(1);
        assertThat(result.getSources().get(0).getSourceType()).isEqualTo(PollutionSourceType.UNKNOWN);
        assertThat(result.getSources().get(0).getEstimatedContributionPercent()).isEqualTo(100);
        assertThat(result.getUnknownContributionPercent()).isEqualTo(100);
        assertThat(result.getOverallConfidence()).isZero();
    }

    @Test
    void contributionTotalsAlwaysEqualOneHundred() {
        CityEnvironmentalContext context = baseContext("MUMBAI")
                .aqi(aqi(214, 98, 142, 62, 24, 1.8, 38))
                .traffic(Map.of("sampleCount", 12, "averageCongestionIndex", 0.78, "averageSpeed", 14.0))
                .industries(List.of(Map.of("facilityId", "I-2", "riskLevel", "HIGH", "complianceStatus", "OK")))
                .construction(List.of(Map.of("permitId", "C-2", "status", "ACTIVE", "dustRiskLevel", "MEDIUM")))
                .population(Map.of("population", 12_000_000, "wardProfileCount", 9))
                .landUse(Map.of("dominantType", "COMMERCIAL"))
                .greenCover(Map.of("greenCoverIndex", 0.18))
                .wind(Map.of("speed", 1.8, "direction", 300))
                .humidity(31)
                .temperature(36)
                .historicalAQI(List.of(Map.of("aqi", 145), Map.of("aqi", 155), Map.of("aqi", 148)))
                .build();

        AttributionResult result = service.attribute(context, request("MUMBAI"));

        int total = result.getSources().stream()
                .mapToInt(PollutionSourceContribution::getContributionPercent)
                .sum();
        assertThat(total).isEqualTo(100);
        assertThat(result.getSources()).allSatisfy(source -> assertThat(source.getEvidence()).isNotEmpty());
    }

    private CityEnvironmentalContext.CityEnvironmentalContextBuilder baseContext(String cityId) {
        return CityEnvironmentalContext.builder()
                .city(cityId)
                .cityId(cityId)
                .timestamp(Instant.parse("2026-07-09T10:00:00Z"))
                .providerStatus(Map.of(
                        "aqi", "SUCCESS",
                        "weather", "SUCCESS",
                        "traffic", "SUCCESS",
                        "construction", "SUCCESS",
                        "industries", "SUCCESS",
                        "landUse", "SUCCESS"
                ))
                .providerConfidence(Map.of(
                        "aqi", 0.92,
                        "weather", 0.90,
                        "traffic", 0.75,
                        "construction", 0.78,
                        "industries", 0.80,
                        "landUse", 0.70,
                        "greenCover", 0.60,
                        "population", 0.80,
                        "historicalAQI", 0.70,
                        "satellite", 0.85
                ));
    }

    private Map<String, Object> aqi(int currentAqi, double pm25, double pm10, double no2, double so2, double co, double o3) {
        return Map.of(
                "currentAqi", currentAqi,
                "stations", List.of(Map.of("pollutants", Map.of(
                        "aqi", currentAqi,
                        "pm25", pm25,
                        "pm10", pm10,
                        "no2", no2,
                        "so2", so2,
                        "co", co,
                        "o3", o3
                )))
        );
    }

    private AttributionRequest request(String cityId) {
        return AttributionRequest.builder()
                .cityId(cityId)
                .wardId("WARD-1")
                .build();
    }
}


