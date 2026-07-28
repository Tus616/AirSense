package com.airsense.api.forecast;

import com.airsense.api.config.AirQualityOperationsProperties;
import com.airsense.api.attribution.PollutionAttributionService;
import com.airsense.api.entities.ForecastModelRegistryEntry;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.history.CanonicalLocationIdentityService;
import com.airsense.api.repositories.ForecastModelRegistryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class ForecastOrchestratorTest {

    @Test
    void insufficientSameStandardHistoryUsesPersistenceBaseline() {
        ForecastResult result = orchestrator().forecast(
                baseContext("LUCKNOW", 168, "INDIA_NAQI")
                        .historicalAQI(history("INDIA_NAQI", 130, 138, 141))
                        .weather(weatherForecast(1.2, 84, 0.0))
                        .build(),
                request("LUCKNOW")
        );

        assertThat(result.getEngine()).isEqualTo("PERSISTENCE_FALLBACK");
        assertThat(result.getForecastStandard()).isEqualTo("INDIA_NAQI");
        assertThat(result.isFallbackUsed()).isTrue();
        assertThat(result.getWarnings()).contains("INSUFFICIENT_SAME_STANDARD_HISTORY");
        result.getForecast().values().forEach(point -> {
            assertThat(point.getPredictedAqi()).isEqualTo(168);
            assertThat(point.isFallbackUsed()).isTrue();
            assertThat(point.getMode()).isEqualTo("PERSISTENCE");
        });
    }

    @Test
    void twentyFourHourTrendCanRunWhileLongerHorizonsUsePersistence() {
        ForecastResult result = orchestrator().forecast(
                baseContext("DELHI", 184, "INDIA_NAQI")
                        .historicalAQI(historySequence("INDIA_NAQI", 24, 142, 2))
                        .weather(weatherForecast(1.0, 82, 0.0))
                        .build(),
                request("DELHI")
        );

        assertThat(result.getEngine()).isEqualTo("MIXED_HORIZON_MODES");
        assertThat(result.getForecast().get("24h").getPredictedAqi()).isGreaterThan(184);
        assertThat(result.getForecast().get("24h").getMode()).isEqualTo("TREND_WEATHER_V1");
        assertThat(result.getForecast().get("48h").getMode()).isEqualTo("PERSISTENCE");
        assertThat(result.getForecast().get("72h").getMode()).isEqualTo("PERSISTENCE");
        assertThat(result.getForecast().get("48h").getInsufficiencyReasons()).contains("INSUFFICIENT_OBSERVATION_COUNT");
    }

    @Test
    void fortyEightHourForecastRequiresItsOwnCountAndCoverage() {
        ForecastResult result = orchestrator().forecast(
                baseContext("NOIDA", 190, "INDIA_NAQI")
                        .historicalAQI(historySequence("INDIA_NAQI", 48, 150, 1))
                        .weather(weatherForecast(2.0, 70, 0.0))
                        .build(),
                request("NOIDA")
        );

        assertThat(result.getForecast().get("24h").getMode()).isEqualTo("TREND_WEATHER_V1");
        assertThat(result.getForecast().get("48h").getMode()).isEqualTo("TREND_WEATHER_V1");
        assertThat(result.getForecast().get("72h").getMode()).isEqualTo("PERSISTENCE");
        assertThat(result.getForecast().get("72h").getInsufficiencyReasons()).contains("INSUFFICIENT_OBSERVATION_COUNT");
    }

    @Test
    void seventyTwoHourForecastRequiresStrongestHistory() {
        ForecastResult result = orchestrator().forecast(
                baseContext("GURUGRAM", 210, "INDIA_NAQI")
                        .historicalAQI(historySequence("INDIA_NAQI", 72, 160, 1))
                        .weather(weatherForecast(2.0, 70, 0.0))
                        .build(),
                request("GURUGRAM")
        );

        assertThat(result.getEngine()).isEqualTo("TREND_WEATHER_V1");
        assertThat(result.getForecast().get("24h").getMode()).isEqualTo("TREND_WEATHER_V1");
        assertThat(result.getForecast().get("48h").getMode()).isEqualTo("TREND_WEATHER_V1");
        assertThat(result.getForecast().get("72h").getMode()).isEqualTo("TREND_WEATHER_V1");
    }

    @Test
    void indianAndUsAqiHistoriesAreNotMixed() {
        List<Map<String, Object>> mixedHistory = new ArrayList<>();
        mixedHistory.addAll(history("US_AQI", 200, 210, 220, 230, 240, 250, 260, 270));
        mixedHistory.addAll(history("INDIA_NAQI", 170, 172, 174, 176, 178));

        ForecastResult result = orchestrator().forecast(
                baseContext("MUMBAI", 180, "INDIA_NAQI")
                        .historicalAQI(mixedHistory)
                        .weather(weatherForecast(2.2, 65, 0.0))
                        .build(),
                request("MUMBAI")
        );

        assertThat(result.getEngine()).isEqualTo("PERSISTENCE_FALLBACK");
        assertThat(result.getObservationCount()).isEqualTo(5);
        assertThat(result.getForecastStandard()).isEqualTo("INDIA_NAQI");
    }

    @Test
    void duplicateTimestampsAreRemovedBeforeTrendSelection() {
        Instant duplicateTimestamp = Instant.now().minusSeconds(3600);
        List<Map<String, Object>> duplicateHistory = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            duplicateHistory.add(Map.of(
                    "aqi", 150 + i,
                    "aqiStandard", "INDIA_NAQI",
                    "providerObservedAt", duplicateTimestamp.toString()
            ));
        }

        ForecastResult result = orchestrator().forecast(
                baseContext("BENGALURU", 166, "INDIA_NAQI")
                        .historicalAQI(duplicateHistory)
                        .weather(weatherForecast(2.5, 55, 0.0))
                        .build(),
                request("BENGALURU")
        );

        assertThat(result.getEngine()).isEqualTo("PERSISTENCE_FALLBACK");
        assertThat(result.getObservationCount()).isEqualTo(1);
        assertThat(result.getForecast().get("72h").getPredictedAqi()).isEqualTo(166);
    }

    @Test
    void missingCurrentAqiReturnsUnavailableForecastWithNullPredictions() {
        ForecastResult result = orchestrator().forecast(
                CityEnvironmentalContext.empty("EMPTY"),
                request("EMPTY")
        );

        assertThat(result.getEngine()).isEqualTo("UNAVAILABLE");
        assertThat(result.getForecast()).containsKeys("24h", "48h", "72h");
        result.getForecast().values().forEach(point -> {
            assertThat(point.getPredictedAqi()).isNull();
            assertThat(point.getLowerBound()).isNull();
            assertThat(point.getUpperBound()).isNull();
        });
    }

    @Test
    void persistenceConfidenceFallsAndUncertaintyWidensWithHorizon() {
        ForecastResult result = orchestrator().forecast(
                baseContext("PUNE", 172, "INDIA_NAQI")
                        .historicalAQI(history("INDIA_NAQI", 150, 152, 155))
                        .weather(Map.of())
                        .build(),
                request("PUNE")
        );

        ForecastPoint h24 = result.getForecast().get("24h");
        ForecastPoint h72 = result.getForecast().get("72h");
        assertThat(h72.getConfidence()).isLessThanOrEqualTo(h24.getConfidence());
        assertThat(h72.getUpperBound() - h72.getPredictedAqi()).isGreaterThanOrEqualTo(h24.getUpperBound() - h24.getPredictedAqi());
    }

    @Test
    void promotedRegistryModelCanSupplyForecastForEligibleHorizon() {
        ForecastOrchestrator orchestrator = orchestrator();
        MlForecastProperties properties = new MlForecastProperties();
        properties.setEnabled(true);
        MlForecastClient client = mock(MlForecastClient.class);
        ForecastModelRegistryRepository registryRepository = mock(ForecastModelRegistryRepository.class);
        ForecastModelRegistryEntry entry = ForecastModelRegistryEntry.builder()
                .modelId("aqi-24h-hgb-v1")
                .aqiStandard("INDIA_NAQI")
                .horizonHours(24)
                .modelScope("GLOBAL")
                .modelFamily("SKLEARN_HIST_GRADIENT_BOOSTING")
                .version("aqi-24h-hgb-v1")
                .promotionStatus("PROMOTED")
                .active(true)
                .promotedAt(Instant.now())
                .build();
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                anyString(), anyInt(), anyString())).thenReturn(Optional.of(entry));
        MlForecastClient.MlForecastPrediction prediction = new MlForecastClient.MlForecastPrediction();
        prediction.setStatus("PROMOTED");
        prediction.setHorizonHours(24);
        prediction.setPredictedAqi(203);
        prediction.setLowerBound(188);
        prediction.setUpperBound(225);
        prediction.setEngine("ML_HIST_GRADIENT_BOOSTING");
        prediction.setModelVersion("aqi-24h-hgb-v1");
        prediction.setConfidence(0.71);
        prediction.setConfidenceLabel("HIGH");
        prediction.setBaselinePredictedAqi(184);
        prediction.setValidationRmse(18.4);
        prediction.setBaselineRmse(25.8);
        when(client.predict(any(MlForecastClient.MlForecastRequest.class))).thenReturn(Optional.of(
                new MlForecastClient.MlForecastResponse("snapshot-1", "in:28.600:77.200", "INDIA_NAQI",
                        Instant.now().toString(), List.of(prediction))));
        ReflectionTestUtils.setField(orchestrator, "configuredMlProperties", properties);
        ReflectionTestUtils.setField(orchestrator, "mlForecastClient", client);
        ReflectionTestUtils.setField(orchestrator, "modelRegistryRepository", registryRepository);

        ForecastResult result = orchestrator.forecast(
                baseContext("DELHI", 184, "INDIA_NAQI")
                        .historicalAQI(historySequence("INDIA_NAQI", 72, 160, 1))
                        .weather(weatherForecast(2.0, 70, 0.0))
                        .build(),
                request("DELHI")
        );

        ForecastPoint point = result.getForecast().get("24h");
        assertThat(point.getMode()).isEqualTo("ML_HIST_GRADIENT_BOOSTING");
        assertThat(point.getPredictedAqi()).isEqualTo(203);
        assertThat(point.getModelPromotionStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(point.getBaselinePredictedAqi()).isEqualTo(184);
        assertThat(point.getValidationRmse()).isEqualTo(18.4);
        assertThat(point.getBaselineRmse()).isEqualTo(25.8);
    }

    @Test
    void globalFallbackSendsGlobalScopeAndDoesNotMasqueradeAsStationModel() {
        ForecastOrchestrator orchestrator = orchestrator();
        MlForecastProperties properties = new MlForecastProperties();
        properties.setEnabled(true);
        MlForecastClient client = mock(MlForecastClient.class);
        ForecastModelRegistryRepository registryRepository = mock(ForecastModelRegistryRepository.class);
        ForecastModelRegistryEntry globalEntry = ForecastModelRegistryEntry.builder()
                .modelId("aqi-24h-xgb-GLOBAL")
                .aqiStandard("INDIA_NAQI")
                .horizonHours(24)
                .modelScope("GLOBAL")
                .modelFamily("XGBOOST")
                .version("aqi-24h-xgb-GLOBAL")
                .promotionStatus("PROMOTED")
                .active(true)
                .promotedAt(Instant.now())
                .build();
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                "INDIA_NAQI", 24, "in:28.600:77.200")).thenReturn(Optional.empty());
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                "INDIA_NAQI", 48, "in:28.600:77.200")).thenReturn(Optional.empty());
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                "INDIA_NAQI", 72, "in:28.600:77.200")).thenReturn(Optional.empty());
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                "INDIA_NAQI", 24, "GLOBAL")).thenReturn(Optional.of(globalEntry));
        when(client.predict(any(MlForecastClient.MlForecastRequest.class))).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "configuredMlProperties", properties);
        ReflectionTestUtils.setField(orchestrator, "mlForecastClient", client);
        ReflectionTestUtils.setField(orchestrator, "modelRegistryRepository", registryRepository);

        orchestrator.forecast(
                baseContext("DELHI", 184, "INDIA_NAQI")
                        .historicalAQI(historySequence("INDIA_NAQI", 72, 160, 1))
                        .weather(weatherForecast(2.0, 70, 0.0))
                        .build(),
                request("DELHI")
        );

        verify(client).predict(argThat(req ->
                req.getCandidateModelScopes().contains("GLOBAL")
                        && "forecasting-feature-schema-v2".equals(req.getFeatures().get("featureSchemaVersion"))
                        && Double.valueOf(28.6).equals(req.getFeatures().get("latitude"))
                        && Double.valueOf(77.2).equals(req.getFeatures().get("longitude"))
                        && req.getHistory().stream().noneMatch(row -> "GLOBAL".equals(row.get("locationKey")))
                        && req.getHistory().stream().allMatch(row -> "in:28.600:77.200".equals(row.get("locationKey")))));
    }

    @Test
    void unpromotedGlobalModelDoesNotBlockPretrainedLiveRequest() {
        ForecastOrchestrator orchestrator = orchestrator();
        MlForecastProperties properties = new MlForecastProperties();
        properties.setEnabled(true);
        MlForecastClient client = mock(MlForecastClient.class);
        ForecastModelRegistryRepository registryRepository = mock(ForecastModelRegistryRepository.class);
        ForecastModelRegistryEntry rejectedGlobal = ForecastModelRegistryEntry.builder()
                .modelId("aqi-24h-xgb-GLOBAL")
                .aqiStandard("INDIA_NAQI")
                .horizonHours(24)
                .modelScope("GLOBAL")
                .promotionStatus("REJECTED_BASELINE_BETTER")
                .active(true)
                .promotedAt(Instant.now())
                .build();
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                anyString(), anyInt(), anyString())).thenReturn(Optional.of(rejectedGlobal));
        when(client.predict(any(MlForecastClient.MlForecastRequest.class))).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "configuredMlProperties", properties);
        ReflectionTestUtils.setField(orchestrator, "mlForecastClient", client);
        ReflectionTestUtils.setField(orchestrator, "modelRegistryRepository", registryRepository);

        ForecastResult result = orchestrator.forecast(
                baseContext("DELHI", 184, "INDIA_NAQI")
                        .historicalAQI(historySequence("INDIA_NAQI", 72, 160, 1))
                        .weather(weatherForecast(2.0, 70, 0.0))
                        .build(),
                request("DELHI")
        );

        assertThat(result.getForecast().get("24h").getMode()).isEqualTo("TREND_WEATHER_V1");
        verify(client).predict(any());
    }

    @Test
    void unavailableModelArtifactFallsBackToExistingForecastPath() {
        ForecastOrchestrator orchestrator = orchestrator();
        MlForecastProperties properties = new MlForecastProperties();
        properties.setEnabled(true);
        MlForecastClient client = mock(MlForecastClient.class);
        ForecastModelRegistryRepository registryRepository = mock(ForecastModelRegistryRepository.class);
        ForecastModelRegistryEntry entry = ForecastModelRegistryEntry.builder()
                .modelId("aqi-24h-hgb-v1")
                .aqiStandard("INDIA_NAQI")
                .horizonHours(24)
                .modelScope("GLOBAL")
                .modelFamily("SKLEARN_HIST_GRADIENT_BOOSTING")
                .version("aqi-24h-hgb-v1")
                .promotionStatus("PROMOTED")
                .active(true)
                .promotedAt(Instant.now())
                .build();
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                anyString(), anyInt(), anyString())).thenReturn(Optional.of(entry));
        MlForecastClient.MlForecastPrediction prediction = new MlForecastClient.MlForecastPrediction();
        prediction.setStatus("ARTIFACT_UNAVAILABLE");
        prediction.setHorizonHours(24);
        prediction.setFallbackReason("MODEL_NOT_PROMOTED");
        when(client.predict(any(MlForecastClient.MlForecastRequest.class))).thenReturn(Optional.of(
                new MlForecastClient.MlForecastResponse("snapshot-1", "in:28.600:77.200", "INDIA_NAQI",
                        Instant.now().toString(), List.of(prediction))));
        ReflectionTestUtils.setField(orchestrator, "configuredMlProperties", properties);
        ReflectionTestUtils.setField(orchestrator, "mlForecastClient", client);
        ReflectionTestUtils.setField(orchestrator, "modelRegistryRepository", registryRepository);

        ForecastResult result = orchestrator.forecast(
                baseContext("LUCKNOW", 168, "INDIA_NAQI")
                        .historicalAQI(history("INDIA_NAQI", 130, 138, 141))
                        .weather(weatherForecast(1.2, 84, 0.0))
                        .build(),
                request("LUCKNOW")
        );

        assertThat(result.getForecast().get("24h").getMode()).isEqualTo("PERSISTENCE");
        assertThat(result.getForecast().get("24h").getPredictedAqi()).isEqualTo(168);
        assertThat(result.getWarnings()).contains("MODEL_NOT_PROMOTED");
    }

    @Test
    void highOodMlRejectionSurfacesDiagnosticsOnFallbackPoint() {
        ForecastOrchestrator orchestrator = orchestrator();
        MlForecastProperties properties = new MlForecastProperties();
        properties.setEnabled(true);
        MlForecastClient client = mock(MlForecastClient.class);
        ForecastModelRegistryRepository registryRepository = mock(ForecastModelRegistryRepository.class);
        ForecastModelRegistryEntry entry = ForecastModelRegistryEntry.builder()
                .modelId("aqi-72h-xgb-GLOBAL_COLD_START")
                .aqiStandard("INDIA_NAQI")
                .horizonHours(72)
                .modelScope("GLOBAL_COLD_START")
                .modelFamily("XGBOOST")
                .version("aqi-72h-xgb-GLOBAL_COLD_START")
                .promotionStatus("PROMOTED")
                .active(true)
                .promotedAt(Instant.now())
                .build();
        when(registryRepository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
                anyString(), anyInt(), anyString())).thenReturn(Optional.of(entry));
        MlForecastClient.MlForecastPrediction prediction = new MlForecastClient.MlForecastPrediction();
        prediction.setStatus("ARTIFACT_UNAVAILABLE");
        prediction.setHorizonHours(72);
        prediction.setFallbackReason("OUT_OF_DISTRIBUTION_FEATURES");
        prediction.setPredictedDelta(42.0);
        prediction.setOodStatus("OUT_OF_DISTRIBUTION");
        prediction.setOodScore(0.42);
        prediction.setOodLevel("OOD_HIGH");
        prediction.setOodFeatures(List.of("currentAqi"));
        prediction.setWarnings(List.of("OUT_OF_DISTRIBUTION_FEATURES"));
        when(client.predict(any(MlForecastClient.MlForecastRequest.class))).thenReturn(Optional.of(
                new MlForecastClient.MlForecastResponse("snapshot-1", "in:28.600:77.200", "INDIA_NAQI",
                        Instant.now().toString(), List.of(prediction))));
        ReflectionTestUtils.setField(orchestrator, "configuredMlProperties", properties);
        ReflectionTestUtils.setField(orchestrator, "mlForecastClient", client);
        ReflectionTestUtils.setField(orchestrator, "modelRegistryRepository", registryRepository);

        ForecastResult result = orchestrator.forecast(
                baseContext("DELHI", 184, "INDIA_NAQI")
                        .historicalAQI(history("INDIA_NAQI", 180, 181, 184))
                        .weather(weatherForecast(2.0, 70, 0.0))
                        .build(),
                request("DELHI")
        );

        ForecastPoint point = result.getForecast().get("72h");
        assertThat(point.getMode()).isEqualTo("PERSISTENCE");
        assertThat(point.getPredictedAqi()).isEqualTo(184);
        assertThat(point.getFallbackReason()).isEqualTo("OUT_OF_DISTRIBUTION_FEATURES");
        assertThat(point.getOodLevel()).isEqualTo("OOD_HIGH");
        assertThat(point.getOodFeatures()).containsExactly("currentAqi");
        assertThat(point.getPredictedDelta()).isEqualTo(42.0);
    }

    private ForecastOrchestrator orchestrator() {
        return new ForecastOrchestrator(
                null,
                new FeatureBuilder(),
                new ForecastExplainer(),
                new PollutionAttributionService(null),
                new CanonicalLocationIdentityService(new AirQualityOperationsProperties())
        );
    }

    private CityEnvironmentalContext.CityEnvironmentalContextBuilder baseContext(String cityId, int currentAqi, String standard) {
        Instant observedAt = Instant.now().minusSeconds(600);
        return CityEnvironmentalContext.builder()
                .city(cityId)
                .cityId(cityId)
                .timestamp(Instant.now())
                .aqi(Map.of(
                        "currentAqi", currentAqi,
                        "selected", Map.of(
                                "currentAqi", currentAqi,
                                "standard", standard,
                                "provider", "CPCB_CAAQMS",
                                "observedAt", observedAt.toString()
                        ),
                        "stations", List.of(Map.of(
                                "sensorId", "S-1",
                                "pollutants", Map.of("aqi", currentAqi, "pm25", 58, "pm10", 82)
                        ))
                ))
                .providerStatus(Map.of("aqi", "SUCCESS", "weather", "SUCCESS"))
                .providerConfidence(Map.of("aqi", 0.92, "weather", 0.90));
    }

    private List<Map<String, Object>> history(String standard, int... values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Instant start = Instant.now().minusSeconds(values.length * 3600L);
        for (int i = 0; i < values.length; i++) {
            rows.add(Map.of(
                    "aqi", values[i],
                    "aqiStandard", standard,
                    "provider", "TEST_HISTORY",
                    "providerObservedAt", start.plusSeconds(i * 3600L).toString()
            ));
        }
        return rows;
    }

    private List<Map<String, Object>> historySequence(String standard, int count, int startAqi, int step) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Instant start = Instant.now().minusSeconds(count * 3600L);
        for (int i = 0; i < count; i++) {
            rows.add(Map.of(
                    "aqi", startAqi + i * step,
                    "aqiStandard", standard,
                    "provider", "TEST_HISTORY",
                    "providerObservedAt", start.plusSeconds(i * 3600L).toString()
            ));
        }
        return rows;
    }

    private Map<String, Object> weatherForecast(double windSpeed, double humidity, double rain) {
        List<Map<String, Object>> hourly = new ArrayList<>();
        Instant start = Instant.now().plusSeconds(3600);
        for (int i = 0; i < 72; i += 3) {
            hourly.add(Map.of(
                    "timestamp", start.plusSeconds(i * 3600L).toString(),
                    "windSpeed", windSpeed,
                    "humidity", humidity,
                    "rainfall", rain,
                    "temperature", 30,
                    "pressure", 1004
            ));
        }
        return Map.of("humidity", humidity, "temperature", 30, "hourlyForecast", hourly);
    }

    private ForecastRequest request(String cityId) {
        return ForecastRequest.builder()
                .cityId(cityId)
                .cityName(cityId)
                .country("India")
                .latitude(28.6)
                .longitude(77.2)
                .wardId("WARD-1")
                .build();
    }
}


