package com.airsense.api.decision;

import com.airsense.api.advisory.HealthAdvisoryResult;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.enforcement.EnforcementResult;
import com.airsense.api.explainability.ExplainabilitySummary;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.geospatial.GeoSpatialSummary;
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
public class DecisionIntelligenceResult {
    private String city;
    private String cityId;
    private Instant generatedAt;
    private String snapshotId;
    private String locationKey;
    private Object snapshotObservedAt;
    private Object snapshotGeneratedAt;
    private Boolean snapshotReused;
    private String locationHash;
    private DecisionSummary summary;
    private RiskAssessment riskAssessment;
    private Integer currentAQI;
    private ForecastResult forecast;
    private AttributionResult attribution;
    private EnforcementResult enforcement;
    private HealthAdvisoryResult advisories;
    @Builder.Default
    private List<PriorityAction> priorityActions = new ArrayList<>();
    private EvidenceBundle evidenceBundle;
    private EngineStatus engineStatus;
    private double overallConfidence;
    private GeoSpatialSummary geospatialSummary;
    private String geospatialEndpoint;
    private ExplainabilitySummary explainabilitySummary;
    private String explainabilityEndpoint;
    @Builder.Default
    private Map<String, Object> environmentalSignals = new HashMap<>();
}
