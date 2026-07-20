package com.airsense.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "air-quality")
public class AirQualityOperationsProperties {
    private boolean indexInitializationEnabled = true;
    private boolean demoDataEnabled = false;
    private String locationKeyVersion = "v2";
    private int locationCoordinatePrecision = 3;
    private boolean locationKeyMigrationEnabled = false;
}
