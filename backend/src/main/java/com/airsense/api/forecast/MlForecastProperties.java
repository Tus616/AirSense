package com.airsense.api.forecast;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "forecast.ml")
public class MlForecastProperties {
    private boolean enabled = false;
    private String serviceUrl = "http://localhost:8000";
    private int timeoutSeconds = 35;
    private String providerForecastStandard = "US_AQI";
    private String providerForecastProvider = "OPEN_METEO";
    private String modelScope = "GLOBAL";
    private int minSamples = 200;
    private double minRmseImprovementPercent = 5.0;
    private double maxAbsoluteBias = 20.0;
}
