package com.airsense.api.forecast;

import com.airsense.api.config.AirQualityOperationsProperties;
import com.airsense.api.entities.AqiForecastRun;
import com.airsense.api.repositories.AqiForecastRunRepository;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import com.airsense.api.history.CanonicalLocationIdentityService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AqiForecastEvaluationServiceTest {

    @Test
    void metricsRemainNullWhenSampleCountIsInsufficient() {
        AqiForecastRunRepository runRepository = mock(AqiForecastRunRepository.class);
        ForecastEngineProperties properties = new ForecastEngineProperties();
        properties.setMetricsMinSamples(10);
        AqiForecastEvaluationService service = new AqiForecastEvaluationService(
                runRepository, mock(AqiHistoricalSnapshotRepository.class), properties,
                new CanonicalLocationIdentityService(new AirQualityOperationsProperties()));
        AqiForecastRun run = AqiForecastRun.builder()
                .locationKey("in:28.600:77.200")
                .forecastStandard("INDIA_NAQI")
                .engine("TREND_WEATHER_V1")
                .modelVersion("trend-weather-v1")
                .horizonHours(24)
                .generatedAt(Instant.now())
                .predictedAqi(190)
                .actualAqi(200)
                .absoluteError(10.0)
                .squaredError(100.0)
                .error(-10.0)
                .baselinePredictedAqi(184)
                .evaluated(true)
                .build();
        when(runRepository.findByLocationKeyInAndEvaluatedTrueAndGeneratedAtBetween(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(run));

        Map<String, Object> response = service.metrics("Delhi", "Delhi", "India", 28.6, 77.2, 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> metric = ((List<Map<String, Object>>) response.get("metrics")).get(0);

        assertThat(metric.get("status")).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(metric.get("mae")).isNull();
        assertThat(metric.get("rmse")).isNull();
        assertThat(metric.get("improvementPercent")).isNull();
    }
}


