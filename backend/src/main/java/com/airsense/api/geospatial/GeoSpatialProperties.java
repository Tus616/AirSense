package com.airsense.api.geospatial;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "geospatial")
public class GeoSpatialProperties {
    private long cacheTtlSeconds = 300;
    private double syntheticGridSizeDegrees = 0.025;
}
