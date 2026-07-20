package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlumeFeatureCollectionDto {
    private String type; // "FeatureCollection"
    private List<PlumeFeatureDto> features;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlumeFeatureDto {
        private String type; // "Feature"
        private PlumePropertiesDto properties;
        private PlumeGeometryDto geometry;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlumePropertiesDto {
        private String pollutant;
        private String level;
        private int forecast_hour;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlumeGeometryDto {
        private String type; // "Polygon"
        private List<List<List<Double>>> coordinates;
    }
}
