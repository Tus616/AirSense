package com.airsense.api.forecast;

import com.airsense.api.entities.Prediction;
import com.airsense.api.services.ForecastClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "legacy.forecast", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ForecastModel {
    private static final List<Integer> HORIZONS = List.of(24, 48, 72);

    private final ForecastClient forecastClient;

    public ModelForecast predict(ForecastFeatures features) {
        if (features == null) {
            return unavailable("missing sensorId in fused AQI station context");
        }

        String sensorId = valueOrDefault(features.getSensorId(), valueOrDefault(features.getCityId(), "UNKNOWN_SENSOR"));
        if (sensorId.isBlank()) {
            return unavailable("missing sensorId in fused AQI station context");
        }

        String wardId = valueOrDefault(features.getWardId(), valueOrDefault(features.getCityId(), "UNKNOWN_WARD"));
        log.info("ForecastModel Fetching AI forecast cityId={} wardId={} sensorId={}",
                features.getCityId(), wardId, sensorId);
        Prediction prediction = forecastClient.fetchForecast(wardId, sensorId);
        if (prediction == null || prediction.getPredictions() == null || prediction.getPredictions().isEmpty()) {
            log.warn("ForecastModel Failure reason=empty_ai_response cityId={}", features.getCityId());
            return unavailable("AI service unavailable or returned no predictions");
        }

        Map<Integer, Integer> forecasts = new HashMap<>();
        Map<Integer, String> categories = new HashMap<>();
        for (Integer horizon : HORIZONS) {
            int index = Math.min(horizon - 1, prediction.getPredictions().size() - 1);
            Prediction.HourlyPrediction hourly = prediction.getPredictions().get(index);
            Integer predictedAqi = hourly.getPredictedAqi();
            if (predictedAqi == null || predictedAqi <= 0) {
                log.warn("ForecastModel Failure reason=invalid_ai_prediction cityId={} horizon={} value={}",
                        features.getCityId(), horizon, predictedAqi);
                return unavailable("AI service returned missing or non-positive AQI for " + horizon + "h");
            }
            forecasts.put(horizon, predictedAqi);
            categories.put(horizon, hourly.getCategory());
        }

        boolean fallbackUsed = Boolean.TRUE.equals(prediction.getFallbackUsed())
                || (prediction.getModelVersion() != null && prediction.getModelVersion().toLowerCase().contains("fallback"));
        double confidence = fallbackUsed ? 0.55 : 0.78;
        log.info("ForecastModel Success cityId={} modelVersion={} horizons={}",
                features.getCityId(), prediction.getModelVersion(), forecasts.keySet());
        return ModelForecast.builder()
                .available(true)
                .predictions(forecasts)
                .categories(categories)
                .modelConfidence(confidence)
                .modelVersion(valueOrDefault(prediction.getModelVersion(), "ai-service"))
                .fallbackUsed(fallbackUsed)
                .build();
    }

    private ModelForecast unavailable(String reason) {
        return ModelForecast.builder()
                .available(false)
                .modelConfidence(0.0)
                .modelVersion("unavailable")
                .fallbackUsed(true)
                .unavailableReason(reason)
                .build();
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
