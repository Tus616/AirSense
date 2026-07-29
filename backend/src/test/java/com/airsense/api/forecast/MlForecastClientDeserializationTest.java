package com.airsense.api.forecast;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MlForecastClientDeserializationTest {
    @Test
    void deserializesOpenMeteoProviderForecastWithChronosDisabledReason() throws Exception {
        String json = """
                {
                  "snapshotId": "snap-delhi",
                  "locationKey": "delhi:coord",
                  "forecastStandard": "US_AQI",
                  "generatedAt": "2026-07-29T00:00:00Z",
                  "extraSafeField": "ignored",
                  "predictions": [
                    {
                      "status": "FORECAST",
                      "horizonHours": 24,
                      "predictedAqi": 156,
                      "lowerBound": 138,
                      "upperBound": 174,
                      "engine": "OPEN_METEO_PROVIDER_FORECAST",
                      "modelFamily": "PROVIDER_NUMERICAL_FORECAST",
                      "modelVersion": "open-meteo-air-quality",
                      "confidence": 0.62,
                      "confidenceLabel": "MEDIUM",
                      "fallbackReason": "CHRONOS_DISABLED",
                      "aqiStandard": "US_AQI",
                      "provider": "OPEN_METEO",
                      "targetTime": "2026-07-30T00:00:00Z",
                      "historyObservationCount": 169,
                      "historyCoverageHours": 168.0,
                      "warnings": []
                    }
                  ]
                }
                """;

        JsonMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        MlForecastClient.MlForecastResponse response = mapper.readValue(json, MlForecastClient.MlForecastResponse.class);

        assertThat(response.getForecastStandard()).isEqualTo("US_AQI");
        assertThat(response.getPredictions()).hasSize(1);
        MlForecastClient.MlForecastPrediction prediction = response.getPredictions().get(0);
        assertThat(prediction.getEngine()).isEqualTo("OPEN_METEO_PROVIDER_FORECAST");
        assertThat(prediction.getFallbackReason()).isEqualTo("CHRONOS_DISABLED");
        assertThat(prediction.getPredictedAqi()).isEqualTo(156);
        assertThat(prediction.getTargetTime()).isEqualTo("2026-07-30T00:00:00Z");
    }
}
