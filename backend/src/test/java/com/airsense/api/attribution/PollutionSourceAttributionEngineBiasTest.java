package com.airsense.api.attribution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PollutionSourceAttributionEngineBiasTest {
    private final PollutionSourceAttributionEngine engine = new PollutionSourceAttributionEngine(new SourceAttributionProperties());

    @Test
    void roadDensityAloneDoesNotCreateHighTrafficAttribution() {
        AttributionResult result = engine.calculate(baseInput()
                .pollutants(Map.of("pm25", 70.0, "pm10", 95.0, "no2", 18.0))
                .geospatial(Map.of("available", true, "roadDensityKmPerSquareKm", 4.0, "nearestMajorRoadDistanceKm", 0.2))
                .temporal(Map.of("localHour", 9, "isRushHour", true))
                .build());

        PollutionSourceContribution traffic = source(result, PollutionSourceType.TRAFFIC);

        assertThat(traffic.getContributionPercent()).isLessThan(40);
        assertThat(traffic.getConfidenceLabel()).isNotEqualTo("HIGH");
        assertThat(result.getUnknownContributionPercent()).isGreaterThanOrEqualTo(38);
    }

    @Test
    void pollutantFingerprintCanJustifyMoreTrafficThanProxyOnlyCase() {
        AttributionResult proxyOnly = engine.calculate(baseInput()
                .pollutants(Map.of("pm25", 70.0, "pm10", 95.0, "no2", 18.0))
                .geospatial(Map.of("available", true, "roadDensityKmPerSquareKm", 4.0, "nearestMajorRoadDistanceKm", 0.2))
                .temporal(Map.of("localHour", 9, "isRushHour", true))
                .build());
        AttributionResult pollutantSupported = engine.calculate(baseInput()
                .pollutants(Map.of("pm25", 70.0, "pm10", 95.0, "no2", 64.0, "co", 1.4))
                .geospatial(Map.of("available", true, "roadDensityKmPerSquareKm", 4.0, "nearestMajorRoadDistanceKm", 0.2))
                .temporal(Map.of("localHour", 9, "isRushHour", true))
                .build());

        assertThat(source(pollutantSupported, PollutionSourceType.TRAFFIC).getContributionPercent())
                .isGreaterThan(source(proxyOnly, PollutionSourceType.TRAFFIC).getContributionPercent());
    }

    @Test
    void missingFireAndSatelliteEvidenceRaiseUnknownShare() {
        AttributionResult missingRemote = engine.calculate(baseInput().build());
        AttributionResult availableRemote = engine.calculate(baseInput()
                .fireEvidence(Map.of("available", true, "relevantDetectionCount", 0, "aggregateFireInfluenceScore", 0.0))
                .satelliteEvidence(Map.of("available", true, "ndviAnomaly", 0.1))
                .historicalContext(Map.of("available", true, "observationCount", 8))
                .build());

        assertThat(missingRemote.getUnknownContributionPercent()).isGreaterThan(availableRemote.getUnknownContributionPercent());
        assertThat(missingRemote.getWarnings()).contains("FIRE_EVIDENCE_UNAVAILABLE", "SATELLITE_EVIDENCE_UNAVAILABLE");
    }

    @Test
    void lowNo2IsReportedAsContradictingTrafficEvidence() {
        AttributionResult result = engine.calculate(baseInput()
                .pollutants(Map.of("pm25", 65.0, "pm10", 85.0, "no2", 12.0))
                .geospatial(Map.of("available", true, "roadDensityKmPerSquareKm", 3.0))
                .build());

        assertThat(source(result, PollutionSourceType.TRAFFIC).getContradictingEvidence())
                .anyMatch(evidence -> "LOW_NO2".equals(evidence.getType()));
    }

    @Test
    void normalizedContributionsAlwaysTotalOneHundredIncludingUnknown() {
        AttributionResult result = engine.calculate(baseInput().build());

        assertThat(result.getSources().stream().mapToInt(PollutionSourceContribution::getContributionPercent).sum())
                .isEqualTo(100);
    }

    @Test
    void identicalEvidenceProducesIdenticalContributionsAcrossCityLabels() {
        PollutionAttributionInput first = baseInput().location(Map.of("city", "Delhi")).build();
        PollutionAttributionInput second = baseInput().location(Map.of("city", "Mumbai")).build();

        assertThat(engine.calculate(first).getSources().stream().map(PollutionSourceContribution::getContributionPercent).toList())
                .isEqualTo(engine.calculate(second).getSources().stream().map(PollutionSourceContribution::getContributionPercent).toList());
    }

    @Test
    void sourceDebugDetailsExposeRawFinalAndNormalizedScores() {
        AttributionResult result = engine.calculate(baseInput().build());

        assertThat(result.getDiagnostics()).containsKey("sourceDebugDetails");
        assertThat(String.valueOf(result.getDiagnostics().get("sourceDebugDetails")))
                .contains("rawScore", "positiveScore", "contradictionPenalty", "normalizedContribution");
    }

    @Test
    void missingEvidenceIsReturnedOnContribution() {
        AttributionResult result = engine.calculate(baseInput()
                .pollutants(Map.of("pm25", 75.0, "pm10", 100.0))
                .geospatial(Map.of("available", false))
                .build());

        assertThat(source(result, PollutionSourceType.TRAFFIC).getMissingEvidence())
                .contains("NO2_UNAVAILABLE", "CO_UNAVAILABLE", "OSM_ROAD_EVIDENCE_UNAVAILABLE");
    }

    private PollutionAttributionInput.PollutionAttributionInputBuilder baseInput() {
        return PollutionAttributionInput.builder()
                .location(Map.of("city", "Test City"))
                .currentAqi(Map.of("value", 180, "provider", "CPCB_CAAQMS", "observedAt", "2026-07-12T10:00:00Z"))
                .pollutants(Map.of("pm25", 82.0, "pm10", 118.0, "no2", 44.0, "co", 0.7))
                .weather(Map.of("humidityPercent", 46.0, "windSpeedMps", 1.4, "rainfallMm", 0.0))
                .temporal(Map.of("localHour", 10, "isRushHour", true))
                .geospatial(Map.of("available", true, "roadDensityKmPerSquareKm", 1.6, "nearestMajorRoadDistanceKm", 0.3, "buildingFeatureCount", 120))
                .fireEvidence(Map.of("available", false))
                .satelliteEvidence(Map.of("available", false))
                .historicalContext(Map.of("available", false, "observationCount", 1))
                .providerStatus(Map.of("aqi", "SUCCESS", "weather", "SUCCESS"))
                .snapshotId("snap-test")
                .locationKey("in:test")
                .snapshotObservedAt("2026-07-12T10:00:00Z")
                .snapshotGeneratedAt("2026-07-12T10:01:00Z")
                .snapshotReused(false)
                .locationHash("loc-test")
                .generatedAt(Instant.parse("2026-07-12T10:01:00Z"));
    }

    private PollutionSourceContribution source(AttributionResult result, PollutionSourceType type) {
        return result.getSources().stream()
                .filter(source -> source.getSourceType() == type)
                .findFirst()
                .orElseThrow();
    }
}
