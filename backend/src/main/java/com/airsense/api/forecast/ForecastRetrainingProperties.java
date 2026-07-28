package com.airsense.api.forecast;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "forecast.retraining")
public class ForecastRetrainingProperties {
    private boolean enabled = false;
    private String dailyQualityCron = "0 15 1 * * *";
    private String trainingCron = "0 30 2 ? * SUN";
    private String schedule = "weekly";
    private String aiServiceDirectory = "";
    private String pythonExecutable = "python";
    private String modelDir = "models";
    private String aqiStandard = "INDIA_NAQI";
    private List<String> scopes = List.of(
            "GLOBAL_COLD_START",
            "GLOBAL_SHORT_HISTORY",
            "GLOBAL_MEDIUM_HISTORY",
            "GLOBAL_FULL_HISTORY"
    );
    private List<Integer> horizons = List.of(24, 48, 72);
    private double maxLiveGapHours = 1.5;
    private int minLiveContiguousHours = 73;
    private int timeoutMinutes = 240;
}
