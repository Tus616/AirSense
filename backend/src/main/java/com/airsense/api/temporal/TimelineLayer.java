package com.airsense.api.temporal;

import com.airsense.api.geospatial.GeoSpatialLayerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineLayer {
    private String layerId;
    private GeoSpatialLayerType layerType;
    private String displayName;
    private String description;
    private double confidence;
    private Instant generatedAt;
    @Builder.Default
    private Map<String, Object> geoJson = new HashMap<>();
    @Builder.Default
    private List<Map<String, Object>> legend = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    @Builder.Default
    private List<String> evidence = new ArrayList<>();
}
