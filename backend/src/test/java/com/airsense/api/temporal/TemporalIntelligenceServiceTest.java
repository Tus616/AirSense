package com.airsense.api.temporal;

import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.DecisionIntelligenceService;
import com.airsense.api.decision.DecisionRequest;
import com.airsense.api.decision.DecisionSummary;
import com.airsense.api.decision.PriorityAction;
import com.airsense.api.decision.RiskAssessment;
import com.airsense.api.decision.SharedDecisionSnapshot;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.geospatial.GeoSpatialIntelligenceResult;
import com.airsense.api.geospatial.GeoSpatialIntelligenceService;
import com.airsense.api.geospatial.GeoSpatialLayer;
import com.airsense.api.geospatial.GeoSpatialLayerType;
import com.airsense.api.geospatial.GeoSpatialRequest;
import com.airsense.api.entities.CityMetricsSnapshot;
import com.airsense.api.repositories.CityMetricsSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalIntelligenceServiceTest {

    @Test
    void returnsHistoricalCurrentAndForecastFrames() {
        TemporalIntelligenceService service = service();

        TimelineResult result = service.timeline(TemporalRequest.builder().cityId("DELHI").build());

        assertThat(result.getFrames()).hasSize(5);
        assertThat(result.getFrames()).extracting(TimelineFrame::getLabel)
                .containsExactly("Historical", "Current", "+24h", "+48h", "+72h");
        assertThat(result.getFrames().get(2).getAqi()).isEqualTo(230);
        assertThat(result.getFrames().get(3).getAqi()).isEqualTo(260);
        assertThat(result.getFrames().get(4).getAqi()).isEqualTo(290);
    }

    @Test
    void liveForecastTimelineCurrentFrameRemainsDecisionBacked() {
        TemporalIntelligenceService service = service();

        TimelineFrame current = service.timeline(TemporalRequest.builder().cityId("DELHI").build()).getFrames().get(1);

        assertThat(current.getLabel()).isEqualTo("Current");
        assertThat(current.getAqi()).isEqualTo(210);
        assertThat(current.getTimestamp()).isEqualTo(Instant.parse("2026-07-10T00:00:00Z"));
        assertThat(current.getDominantSource()).isEqualTo("TRAFFIC");
    }

    @Test
    void framesContainValidGeoJsonLayersAndHotspots() {
        TemporalIntelligenceService service = service();

        TimelineResult result = service.timeline(TemporalRequest.builder().cityId("DELHI").build());

        for (TimelineFrame frame : result.getFrames()) {
            assertThat(frame.getGeoJsonLayers()).isNotEmpty();
            assertThat(frame.getGeoJsonLayers()).allSatisfy(layer -> {
                assertThat(layer.getGeoJson()).containsEntry("type", "FeatureCollection");
                assertThat(layer.getGeoJson().get("features")).isInstanceOf(List.class);
            });
            assertThat(frame.getHotspots()).isNotEmpty();
        }
    }

    @Test
    void futureFramesUseMatchingForecastLayerOnly() {
        TemporalIntelligenceService service = service();

        TimelineFrame plus48 = service.timeline(TemporalRequest.builder().cityId("DELHI").build())
                .getFrames()
                .stream()
                .filter(frame -> frame.getOffsetHours() == 48)
                .findFirst()
                .orElseThrow();

        assertThat(plus48.getGeoJsonLayers()).anyMatch(layer -> layer.getLayerType() == GeoSpatialLayerType.FORECAST_GRID_48H);
        assertThat(plus48.getGeoJsonLayers()).noneMatch(layer -> layer.getLayerType() == GeoSpatialLayerType.FORECAST_GRID_24H);
        assertThat(plus48.getGeoJsonLayers()).noneMatch(layer -> layer.getLayerType() == GeoSpatialLayerType.FORECAST_GRID_72H);
    }

    @Test
    void cacheAvoidsRepeatedEngineCalls() {
        DecisionIntelligenceService decisionService = Mockito.mock(DecisionIntelligenceService.class);
        GeoSpatialIntelligenceService geoSpatialService = Mockito.mock(GeoSpatialIntelligenceService.class);
        CityMetricsSnapshotRepository snapshotRepository = Mockito.mock(CityMetricsSnapshotRepository.class);
        when(decisionService.decide(any(DecisionRequest.class))).thenReturn(decision());
        when(geoSpatialService.generate(any(GeoSpatialRequest.class))).thenReturn(geospatial());
        when(snapshotRepository.findByCityIdOrderByTimestampDesc("DELHI")).thenReturn(List.of());
        TemporalIntelligenceService service = new TemporalIntelligenceService(decisionService, geoSpatialService, snapshotRepository);
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 300L);

        TimelineResult first = service.timeline(TemporalRequest.builder().cityId("DELHI").build());
        TimelineResult second = service.timeline(TemporalRequest.builder().cityId("DELHI").build());

        assertThat(first.getCacheStatus()).isEqualTo("MISS");
        assertThat(second.getCacheStatus()).isEqualTo("HIT");
        verify(decisionService, times(1)).decide(any(DecisionRequest.class));
        verify(geoSpatialService, times(1)).generate(any(GeoSpatialRequest.class));
    }

    @Test
    void historicalFrameUsesStoredSnapshotWhenAvailable() {
        DecisionIntelligenceService decisionService = Mockito.mock(DecisionIntelligenceService.class);
        GeoSpatialIntelligenceService geoSpatialService = Mockito.mock(GeoSpatialIntelligenceService.class);
        CityMetricsSnapshotRepository snapshotRepository = Mockito.mock(CityMetricsSnapshotRepository.class);
        when(decisionService.decide(any(DecisionRequest.class))).thenReturn(decision());
        when(geoSpatialService.generate(any(GeoSpatialRequest.class))).thenReturn(geospatial());
        CityMetricsSnapshot snapshot = new CityMetricsSnapshot();
        snapshot.setCityId("DELHI");
        snapshot.setTimestamp(Instant.parse("2026-07-09T00:00:00Z"));
        snapshot.setCurrentAqi(188);
        when(snapshotRepository.findByCityIdOrderByTimestampDesc("DELHI")).thenReturn(List.of(snapshot));
        TemporalIntelligenceService service = new TemporalIntelligenceService(decisionService, geoSpatialService, snapshotRepository);
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 300L);

        TimelineFrame historical = service.timeline(TemporalRequest.builder().cityId("DELHI").build()).getFrames().get(0);

        assertThat(historical.getAqi()).isEqualTo(188);
        assertThat(historical.getTimestamp()).isEqualTo(snapshot.getTimestamp());
    }

    @Test
    void frameDecisionUpdatesRiskOverviewAqi() {
        TemporalIntelligenceService service = service();

        TimelineFrame plus72 = service.timeline(TemporalRequest.builder().cityId("DELHI").build()).getFrames().get(4);

        assertThat(plus72.getDecision().getCurrentAQI()).isEqualTo(290);
        assertThat(plus72.getDecision().getRiskAssessment().getOverallRiskLevel()).isEqualTo("HIGH");
        assertThat(plus72.getDecision().getSummary().getWhatIsHappening()).contains("+72h frame AQI is 290");
    }

    @Test
    void framesPreserveSharedSnapshotAndForecastProvenance() {
        TemporalIntelligenceService service = service();

        TimelineFrame plus24 = service.timeline(TemporalRequest.builder().cityId("DELHI").build()).getFrames().get(2);

        assertThat(plus24.getDecision().getSnapshotId()).isEqualTo("snap-test-DELHI");
        assertThat(plus24.getDecision().getLocationHash()).isEqualTo("hash-test");
        assertThat(plus24.getDecision().getSharedSnapshot().getCurrentProvider()).isEqualTo("CPCB_CAAQMS");
        assertThat(plus24.getForecast().getEngine()).isEqualTo("OPEN_METEO_PROVIDER_FORECAST");
        assertThat(plus24.getForecast().getForecastStandard()).isEqualTo("US_AQI");
        assertThat(plus24.getForecast().getCurrentProvider()).isEqualTo("CPCB_CAAQMS");
        assertThat(plus24.getForecast().getStationLocationKey()).isEqualTo("provider:station:ITO");
    }

    private TemporalIntelligenceService service() {
        DecisionIntelligenceService decisionService = Mockito.mock(DecisionIntelligenceService.class);
        GeoSpatialIntelligenceService geoSpatialService = Mockito.mock(GeoSpatialIntelligenceService.class);
        CityMetricsSnapshotRepository snapshotRepository = Mockito.mock(CityMetricsSnapshotRepository.class);
        when(decisionService.decide(any(DecisionRequest.class))).thenReturn(decision());
        when(geoSpatialService.generate(any(GeoSpatialRequest.class))).thenReturn(geospatial());
        when(snapshotRepository.findByCityIdOrderByTimestampDesc("DELHI")).thenReturn(List.of());
        TemporalIntelligenceService service = new TemporalIntelligenceService(decisionService, geoSpatialService, snapshotRepository);
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 300L);
        return service;
    }

    private DecisionIntelligenceResult decision() {
        return DecisionIntelligenceResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .generatedAt(Instant.parse("2026-07-10T00:00:00Z"))
                .snapshotId("snap-test-DELHI")
                .sharedSnapshot(SharedDecisionSnapshot.builder()
                        .snapshotId("snap-test-DELHI")
                        .searchedLocationKey("DELHI")
                        .stationLocationKey("provider:station:ITO")
                        .stationName("ITO, Delhi - CPCB")
                        .currentAqi(210)
                        .currentAqiStandard("INDIA_NAQI")
                        .currentProvider("CPCB_CAAQMS")
                        .forecastStandard("US_AQI")
                        .build())
                .locationKey("provider:station:ITO")
                .snapshotObservedAt("2026-07-10T00:00:00Z")
                .snapshotGeneratedAt(Instant.parse("2026-07-10T00:00:00Z"))
                .snapshotReused(false)
                .locationHash("hash-test")
                .currentAQI(210)
                .overallConfidence(0.74)
                .summary(DecisionSummary.builder()
                        .whatIsHappening("Current AQI is 210.")
                        .whyIsItHappening("Traffic and trapped weather dominate.")
                        .whatWillHappenNext("Forecast worsens.")
                        .whatShouldOfficialsDoNow("Divert traffic.")
                        .whatShouldCitizensDoNow("Reduce outdoor exposure.")
                        .build())
                .riskAssessment(RiskAssessment.builder()
                        .currentRisk("HIGH")
                        .forecastRisk("HIGH")
                        .dominantSourceRisk("DISPERSION_RISK")
                        .populationExposureRisk("HIGH")
                        .sensitiveZoneRisk("HIGH")
                        .overallRiskLevel("HIGH")
                        .build())
                .forecast(ForecastResult.builder()
                        .cityId("DELHI")
                        .snapshotId("snap-test-DELHI")
                        .locationHash("hash-test")
                        .currentAqi(210)
                        .forecastStandard("US_AQI")
                        .currentProvider("CPCB_CAAQMS")
                        .engine("OPEN_METEO_PROVIDER_FORECAST")
                        .stationKey("station-ito")
                        .stationName("ITO, Delhi - CPCB")
                        .stationLocationKey("provider:station:ITO")
                        .overallConfidence(0.72)
                        .overallTrend("worsening")
                        .forecast(Map.of(
                                "24h", point(24, 230),
                                "48h", point(48, 260),
                                "72h", point(72, 290)
                        ))
                        .build())
                .attribution(AttributionResult.builder()
                        .dominantSource(PollutionSourceType.TRAFFIC)
                        .overallConfidence(0.78)
                        .build())
                .priorityActions(List.of(PriorityAction.builder()
                        .sourceEngine("enforcement")
                        .actionType("TRAFFIC_DIVERSION")
                        .message("Divert traffic near hotspots.")
                        .priorityScore(82)
                        .confidence(0.74)
                        .build()))
                .build();
    }

    private ForecastPoint point(int hours, int aqi) {
        return ForecastPoint.builder()
                .horizonHours(hours)
                .forecastAt(Instant.parse("2026-07-10T00:00:00Z").plusSeconds(hours * 3600L))
                .predictedAqi(aqi)
                .lowerBound(aqi - 10)
                .upperBound(aqi + 10)
                .aqiCategory("HIGH")
                .confidence(0.72)
                .trend("worsening")
                .expectedDominantSource(PollutionSourceType.TRAFFIC)
                .healthRiskLevel("UNHEALTHY")
                .build();
    }

    private GeoSpatialIntelligenceResult geospatial() {
        return GeoSpatialIntelligenceResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .generatedAt(Instant.parse("2026-07-10T00:00:00Z"))
                .geometrySource("synthetic_grid")
                .layers(List.of(
                        layer(GeoSpatialLayerType.AQI_HOTSPOTS),
                        layer(GeoSpatialLayerType.FORECAST_GRID_24H),
                        layer(GeoSpatialLayerType.FORECAST_GRID_48H),
                        layer(GeoSpatialLayerType.FORECAST_GRID_72H),
                        layer(GeoSpatialLayerType.WIND_VECTOR_LAYER)
                ))
                .metadata(Map.of("center", Map.of("latitude", 28.6139, "longitude", 77.2090)))
                .build();
    }

    private GeoSpatialLayer layer(GeoSpatialLayerType type) {
        return GeoSpatialLayer.builder()
                .layerId(type.name())
                .layerType(type)
                .displayName(type.name())
                .confidence(0.70)
                .generatedAt(Instant.parse("2026-07-10T00:00:00Z"))
                .geoJson(Map.of(
                        "type", "FeatureCollection",
                        "features", List.of(Map.of(
                                "type", "Feature",
                                "geometry", Map.of("type", "Point", "coordinates", List.of(77.2090, 28.6139)),
                                "properties", Map.of("aqi", 210, "predictedAqi", 210, "riskLevel", "HIGH")
                        ))
                ))
                .metadata(Map.of("status", "SUCCESS"))
                .evidence(List.of("test evidence"))
                .build();
    }
}


