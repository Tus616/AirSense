package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sensor_data")
@CompoundIndexes({
    @CompoundIndex(name = "sensor_time_idx", def = "{'sensorId': 1, 'timestamp': -1}")
})
public class SensorData {
    @Id
    private String id;
    
    @Indexed
    private Instant timestamp;
    
    private String sensorId;
    private String stationName;
    private String cityId;
    private String cityName;
    private String wardId;
    private String zoneId;
    
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint coordinates;
    
    private Pollutants pollutants;
    private Weather weather;
    private Traffic traffic;
    private Satellite satellite;
    private LandUse landUse;
    
    private QualityFlags qualityFlags;
    private SourceMetadata sourceMetadata;

    // Nested classes for sub-documents

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeoJsonPoint {
        @Builder.Default
        private String type = "Point";
        private List<Double> coordinates; // [longitude, latitude]
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pollutants {
        private Double pm25;
        private Double pm10;
        private Double co;
        private Double no2;
        private Double so2;
        private Double o3;
        private Integer aqi;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Weather {
        private Double windSpeed;
        private Double windDirection;
        private Double temperature;
        private Double humidity;
        private Double pressure;
        private Double rainfall;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Traffic {
        private Double congestionIndex;
        private Double averageSpeed;
        private Double flow;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Satellite {
        private Double no2Column;
        private Double aerosolIndex;
        private Double cloudFraction;
        private Map<String, Object> sourceFields;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LandUse {
        private String primaryType;
        private List<String> tags;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityFlags {
        private List<String> missingFields;
        private List<String> imputedFields;
        private Boolean anomalySmoothed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceMetadata {
        private List<String> sourceNames;
        private Instant fetchedAt;
        private Map<String, String> rawObjectRefs; // Store references to raw S3/GCS files if needed
    }
}
