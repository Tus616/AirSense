package com.airsense.api.data;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "real-data")
public class RealWorldDataProperties {
    private OpenStreetMap openStreetMap = new OpenStreetMap();
    private Map<String, String> geojson = new HashMap<>();

    @Data
    public static class OpenStreetMap {
        private boolean enabled = true;
        private String overpassUrl = "https://overpass-api.de/api/interpreter";
        private int radiusMeters = 8000;
        private int timeoutSeconds = 18;
    }
}
