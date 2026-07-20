package com.airsense.api.geospatial;

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
public class GeoSpatialIntelligenceResult {
    private String city;
    private String cityId;
    private Instant generatedAt;
    private String geometrySource;
    private boolean degradedMode;
    @Builder.Default
    private List<GeoSpatialLayer> layers = new ArrayList<>();
    @Builder.Default
    private Map<String, String> layerStatus = new HashMap<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
