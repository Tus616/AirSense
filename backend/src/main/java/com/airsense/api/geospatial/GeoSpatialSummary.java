package com.airsense.api.geospatial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoSpatialSummary {
    private int layerCount;
    private String geometrySource;
    private double confidence;
    private boolean degradedMode;
}
