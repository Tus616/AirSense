package com.airsense.api.geospatial;

import com.airsense.api.advisory.HealthAdvisoryResult;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.enforcement.EnforcementActionType;
import com.airsense.api.enforcement.EnforcementRecommendation;
import com.airsense.api.enforcement.EnforcementResult;
import com.airsense.api.forecast.ForecastExplanation;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.data.GeoJsonImportService;
import com.airsense.api.data.OpenStreetMapDataService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

class GeoSpatialIntelligenceServiceTest {

    @Test
    void allLayersReturnedAndNoNullLayers() {
        GeoSpatialIntelligenceResult result = service().assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision());

        assertThat(result.getLayers()).hasSize(12);
        assertThat(result.getLayers()).allSatisfy(layer -> {
            assertThat(layer).isNotNull();
            assertThat(layer.getGeoJson()).isNotNull();
            assertThat(layer.getConfidence()).isGreaterThan(0.0);
        });
        assertThat(result.getLayers()).extracting(GeoSpatialLayer::getLayerType)
                .containsExactlyInAnyOrder(GeoSpatialLayerType.values());
    }

    @Test
    void everyLayerDeclaresAllowedDataOrigin() {
        GeoSpatialIntelligenceResult result = service().assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision());

        assertThat(result.getLayers()).allSatisfy(layer -> assertThat(layer.getMetadata().get("dataOrigin"))
                .isIn("OBSERVED", "DERIVED_FROM_REAL_DATA", "FORECAST", "UNAVAILABLE"));
    }

    @Test
    void everyLayerReturnsValidFeatureCollection() {
        GeoSpatialIntelligenceResult result = service().assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision());

        assertThat(result.getLayers()).allSatisfy(layer -> {
            assertThat(layer.getGeoJson()).containsEntry("type", "FeatureCollection");
            assertThat(layer.getGeoJson().get("features")).isInstanceOf(List.class);
        });
        assertThat((List<?>) layer(result, GeoSpatialLayerType.WIND_VECTOR_LAYER).getGeoJson().get("features")).isNotEmpty();
    }

    @Test
    void liveModeMarksUnavailableWhenGeometryUnavailable() {
        GeoSpatialIntelligenceResult result = service().assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision());

        GeoSpatialLayer construction = layer(result, GeoSpatialLayerType.CONSTRUCTION_SITES);
        assertThat(construction.getMetadata()).containsEntry("geometrySource", "unavailable");
        assertThat(construction.getMetadata()).containsEntry("status", "INSUFFICIENT_EVIDENCE");
        assertThat(construction.getConfidence()).isLessThan(0.8);
    }

    @Test
    void forecastHorizonLayersAreSeparate() {
        GeoSpatialIntelligenceResult result = service().assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision());

        assertThat(layer(result, GeoSpatialLayerType.FORECAST_GRID_24H).getDisplayName()).contains("24h");
        assertThat(layer(result, GeoSpatialLayerType.FORECAST_GRID_48H).getDisplayName()).contains("48h");
        assertThat(layer(result, GeoSpatialLayerType.FORECAST_GRID_72H).getDisplayName()).contains("72h");
    }

    @Test
    void missingSatelliteFallbackIsMarked() {
        GeoSpatialIntelligenceResult result = service().assemble(context(true), attribution(), forecast(), enforcement(), advisory(), decision());

        GeoSpatialLayer satellite = layer(result, GeoSpatialLayerType.SATELLITE_EVIDENCE_LAYER);
        assertThat(satellite.getMetadata()).containsEntry("status", "FALLBACK");
        assertThat(satellite.getEvidence()).anyMatch(value -> value.contains("Measurement fallback: true"));
    }

    @Test
    void windVectorLayerUsesLineStringGeometry() {
        GeoSpatialIntelligenceResult result = service().assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision());

        GeoSpatialLayer wind = layer(result, GeoSpatialLayerType.WIND_VECTOR_LAYER);
        List<?> features = (List<?>) wind.getGeoJson().get("features");
        Map<?, ?> feature = (Map<?, ?>) features.get(0);
        Map<?, ?> geometry = (Map<?, ?>) feature.get("geometry");
        assertThat(geometry.get("type")).isEqualTo("LineString");
    }

    @Test
    void providerContextIndustrialCoordinatesDoNotCreateLiveGeometry() {
        GeoSpatialIntelligenceResult result = service().assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision());

        GeoSpatialLayer industrial = layer(result, GeoSpatialLayerType.INDUSTRIAL_ZONES);
        assertThat(industrial.getMetadata()).containsEntry("geometrySource", "unavailable");
        assertThat(industrial.getMetadata()).containsEntry("status", "INSUFFICIENT_EVIDENCE");
    }

    @Test
    void osmRoadsReplaceSyntheticTrafficGeometry() {
        OpenStreetMapDataService osm = Mockito.mock(OpenStreetMapDataService.class);
        Map<String, Object> osmData = Map.of("roads", Map.of("type", "FeatureCollection", "features", List.of(realLineFeature("road-1"))));
        when(osm.fetch(anyDouble(), anyDouble())).thenReturn(Optional.of(osmData));
        when(osm.features(osmData, "roads")).thenReturn(List.of(realLineFeature("road-1")));
        GeoSpatialIntelligenceService service = service(osm, null);

        GeoSpatialLayer traffic = layer(service.assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision()), GeoSpatialLayerType.TRAFFIC_CORRIDORS);

        assertThat(traffic.getMetadata()).containsEntry("geometrySource", "openstreetmap");
        assertThat(traffic.getEvidence()).anyMatch(value -> value.contains("OSM road features: 1"));
    }

    @Test
    void importedGeoJsonReplacesIndustrialFallback() {
        GeoJsonImportService imports = Mockito.mock(GeoJsonImportService.class);
        when(imports.load("industrial")).thenReturn(Optional.of(Map.of("type", "FeatureCollection", "features", List.of(realPolygonFeature("industrial-1")))));
        GeoSpatialIntelligenceService service = service(null, imports);

        GeoSpatialLayer industrial = layer(service.assemble(context(false), attribution(), forecast(), enforcement(), advisory(), decision()), GeoSpatialLayerType.INDUSTRIAL_ZONES);

        assertThat(industrial.getMetadata()).containsEntry("geometrySource", "geojson_import");
        assertThat(industrial.getEvidence()).anyMatch(value -> value.contains("Imported industrial features: 1"));
    }

    private GeoSpatialIntelligenceService service() {
        return service(null, null);
    }

    private GeoSpatialIntelligenceService service(OpenStreetMapDataService osm, GeoJsonImportService imports) {
        GeoSpatialProperties properties = new GeoSpatialProperties();
        properties.setCacheTtlSeconds(300);
        properties.setSyntheticGridSizeDegrees(0.025);
        return new GeoSpatialIntelligenceService(null, null, null, null, null, null, properties, osm, imports);
    }

    private GeoSpatialLayer layer(GeoSpatialIntelligenceResult result, GeoSpatialLayerType type) {
        return result.getLayers().stream()
                .filter(layer -> layer.getLayerType() == type)
                .findFirst()
                .orElseThrow();
    }

    private CityEnvironmentalContext context(boolean satelliteFallback) {
        return CityEnvironmentalContext.builder()
                .city("Delhi")
                .cityId("DELHI")
                .timestamp(Instant.now())
                .coordinates(Map.of("latitude", 28.6139, "longitude", 77.2090))
                .aqi(Map.of("currentAqi", 260))
                .weather(Map.of("temperature", 31.0, "humidity", 70.0))
                .wind(Map.of("speed", 2.5, "direction", 270.0))
                .traffic(Map.of("sampleCount", 4, "averageCongestionIndex", 78.0))
                .construction(List.of(Map.of("permitId", "C-1", "dustRiskLevel", "HIGH")))
                .industries(List.of(Map.of(
                        "facilityName", "Industrial Estate",
                        "riskLevel", "HIGH",
                        "coordinates", Map.of("latitude", 28.62, "longitude", 77.23)
                )))
                .population(Map.of("schoolsCount", 2, "hospitalsCount", 1, "population", 12_000_000))
                .greenCover(Map.of("greenCoverIndex", 0.21))
                .satellite(Map.of("available", !satelliteFallback, "measurementFallback", satelliteFallback, "summary", "Satellite evidence available"))
                .providerConfidence(Map.of(
                        "aqi", 0.90,
                        "weather", 0.82,
                        "traffic", 0.72,
                        "construction", 0.62,
                        "industries", 0.80,
                        "population", 0.74,
                        "greenCover", 0.35,
                        "satellite", satelliteFallback ? 0.35 : 0.76
                ))
                .build();
    }

    private AttributionResult attribution() {
        return AttributionResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .timestamp(Instant.now())
                .dominantSource(PollutionSourceType.TRAFFIC)
                .overallConfidence(0.78)
                .explanation("Traffic is the dominant source.")
                .sources(List.of(
                        PollutionSourceContribution.builder()
                                .sourceType(PollutionSourceType.TRAFFIC)
                                .contributionPercent(60)
                                .confidence(0.78)
                                .datasetsUsed(List.of("traffic", "aqi", "weather"))
                                .build(),
                        PollutionSourceContribution.builder()
                                .sourceType(PollutionSourceType.ROAD_DUST_CONSTRUCTION)
                                .contributionPercent(25)
                                .confidence(0.63)
                                .datasetsUsed(List.of("construction", "aqi"))
                                .build()
                ))
                .build();
    }

    private ForecastResult forecast() {
        return ForecastResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .generatedAt(Instant.now())
                .overallTrend("worsening")
                .overallConfidence(0.76)
                .forecast(Map.of(
                        "24h", point(24, 280),
                        "48h", point(48, 300),
                        "72h", point(72, 240)
                ))
                .build();
    }

    private ForecastPoint point(int horizon, int aqi) {
        return ForecastPoint.builder()
                .horizonHours(horizon)
                .predictedAqi(aqi)
                .confidence(0.72)
                .expectedDominantSource(PollutionSourceType.TRAFFIC)
                .explanation(ForecastExplanation.builder()
                        .summary("AQI forecast for " + horizon + " hours")
                        .dataUsed(List.of("forecast", "weather", "aqi"))
                        .build())
                .build();
    }

    private EnforcementResult enforcement() {
        return EnforcementResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .generatedAt(Instant.now())
                .currentAqi(260)
                .forecastPeakAqi(300)
                .dominantSource("TRAFFIC")
                .recommendations(List.of(EnforcementRecommendation.builder()
                        .actionType(EnforcementActionType.TRAFFIC_DIVERSION)
                        .priorityScore(82)
                        .responsibleAgency("Traffic Police")
                        .reason("Divert congestion near hotspot")
                        .confidence(0.78)
                        .datasetsUsed(List.of("traffic", "forecast"))
                        .build()))
                .build();
    }

    private HealthAdvisoryResult advisory() {
        return HealthAdvisoryResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .currentAqi(260)
                .forecastPeakAqi(300)
                .dominantSource("TRAFFIC")
                .build();
    }

    private DecisionIntelligenceResult decision() {
        return DecisionIntelligenceResult.builder()
                .city("Delhi")
                .cityId("DELHI")
                .generatedAt(Instant.now())
                .currentAQI(260)
                .overallConfidence(0.76)
                .build();
    }

    private Map<String, Object> realLineFeature(String id) {
        return Map.of(
                "type", "Feature",
                "id", id,
                "geometry", Map.of("type", "LineString", "coordinates", List.of(List.of(77.20, 28.61), List.of(77.22, 28.62))),
                "properties", Map.of("source", "openstreetmap")
        );
    }

    private Map<String, Object> realPolygonFeature(String id) {
        return Map.of(
                "type", "Feature",
                "id", id,
                "geometry", Map.of("type", "Polygon", "coordinates", List.of(List.of(
                        List.of(77.20, 28.61),
                        List.of(77.22, 28.61),
                        List.of(77.22, 28.63),
                        List.of(77.20, 28.63),
                        List.of(77.20, 28.61)
                ))),
                "properties", Map.of("source", "geojson_import")
        );
    }
}


