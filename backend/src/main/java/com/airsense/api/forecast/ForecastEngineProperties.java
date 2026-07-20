package com.airsense.api.forecast;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "forecast.engine")
public class ForecastEngineProperties {
    private int historyWindowHours = 168;
    private int minValidObservations = 12;
    private Integer minObservations24h = 24;
    private Integer minObservations48h = 48;
    private Integer minObservations72h = 72;
    private Integer minCoverageHours24h = 18;
    private Integer minCoverageHours48h = 36;
    private Integer minCoverageHours72h = 60;
    private int preferredObservations = 48;
    private Integer preferredObservations24h = 48;
    private Integer preferredObservations48h = 72;
    private Integer preferredObservations72h = 120;
    private int maxObservationGapHours = 6;
    private int currentDataMaxAgeMinutes = 180;
    private boolean enableTrendWeatherEngine = true;
    private boolean enablePersistenceBaseline = true;
    private double confidenceFloor = 0.20;
    private String modelVersion = "trend-weather-v1";
    private double lowWindThresholdMps = 1.5;
    private double highWindThresholdMps = 5.0;
    private double lowWindIncreasePerDay = 8.0;
    private double highWindDecreasePerDay = 6.0;
    private double rainfallDecreasePerMm = 3.0;
    private double highHumidityThresholdPercent = 75.0;
    private double highHumidityIncreasePerDay = 4.0;
    private int evaluationToleranceMinutes = 120;
    private boolean evaluationEnabled = true;
    private int metricsMinSamples = 10;
    private int metricsPreferredSamples = 30;

    public int requiredObservations(int horizonHours) {
        Integer configured = switch (horizonHours) {
            case 24 -> minObservations24h;
            case 48 -> minObservations48h;
            case 72 -> minObservations72h;
            default -> null;
        };
        return configured != null && configured > 0 ? configured : minValidObservations;
    }

    public int requiredCoverageHours(int horizonHours) {
        Integer configured = switch (horizonHours) {
            case 24 -> minCoverageHours24h;
            case 48 -> minCoverageHours48h;
            case 72 -> minCoverageHours72h;
            default -> null;
        };
        return configured != null && configured > 0 ? configured : Math.max(1, horizonHours / 2);
    }

    public int preferredObservations(int horizonHours) {
        Integer configured = switch (horizonHours) {
            case 24 -> preferredObservations24h;
            case 48 -> preferredObservations48h;
            case 72 -> preferredObservations72h;
            default -> null;
        };
        return configured != null && configured > 0 ? configured : preferredObservations;
    }
}
