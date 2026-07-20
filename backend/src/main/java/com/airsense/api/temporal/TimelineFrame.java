package com.airsense.api.temporal;

import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.PriorityAction;
import com.airsense.api.decision.RiskAssessment;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.geospatial.GeoSpatialIntelligenceResult;
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
public class TimelineFrame {
    private String frameId;
    private String label;
    private String frameType;
    private int offsetHours;
    private Instant timestamp;
    private int aqi;
    private ForecastPoint activeForecastPoint;
    private ForecastResult forecast;
    private AttributionResult attribution;
    private String dominantSource;
    @Builder.Default
    private List<PriorityAction> recommendations = new ArrayList<>();
    private RiskAssessment risk;
    @Builder.Default
    private Map<String, Object> wind = new HashMap<>();
    @Builder.Default
    private List<Map<String, Object>> hotspots = new ArrayList<>();
    @Builder.Default
    private List<TimelineLayer> geoJsonLayers = new ArrayList<>();
    private GeoSpatialIntelligenceResult geospatial;
    private DecisionIntelligenceResult decision;
    @Builder.Default
    private List<TimelineEvent> events = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
