package com.airsense.api.forecast;

import org.springframework.stereotype.Component;

@Component
public class ConfidenceEstimator {
    public ForecastConfidence estimate(ForecastFeatures features, ModelForecast modelForecast, int horizonHours, boolean fallbackUsed) {
        double modelConfidence = fallbackUsed ? 0.35 : clamp(modelForecast.getModelConfidence(), 0.0, 1.0);
        double inputCompleteness = clamp(features.getInputCompleteness(), 0.0, 1.0);
        double providerConfidence = clamp(features.getProviderConfidence(), 0.0, 1.0);
        double horizonConfidence = horizonConfidence(horizonHours);
        double dataFreshness = clamp(features.getDataFreshness(), 0.0, 1.0);
        double finalConfidence = modelConfidence * 0.30
                + inputCompleteness * 0.25
                + providerConfidence * 0.20
                + horizonConfidence * 0.15
                + dataFreshness * 0.10;

        if (features.getCurrentAqi() <= 0 && features.getHistoricalAqiAverage() <= 0) {
            finalConfidence = Math.min(finalConfidence, 0.22);
        }

        return ForecastConfidence.builder()
                .modelConfidence(round(modelConfidence))
                .inputCompleteness(round(inputCompleteness))
                .providerConfidence(round(providerConfidence))
                .horizonConfidence(round(horizonConfidence))
                .dataFreshness(round(dataFreshness))
                .finalConfidence(round(clamp(finalConfidence, 0.05, 0.98)))
                .build();
    }

    private double horizonConfidence(int horizonHours) {
        if (horizonHours <= 24) return 0.95;
        if (horizonHours <= 48) return 0.78;
        return 0.62;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
