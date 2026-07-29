package com.airsense.api.enforcement;

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
public class EnforcementRecommendation {
    private String recommendationId;
    private String city;
    private String wardId;
    private Integer priority;
    private int priorityScore;
    private String priorityLevel;
    private EnforcementActionType actionType;
    private String responsibleAgency;
    private String targetArea;
    @Builder.Default
    private Map<String, Object> location = new HashMap<>();
    private String reason;
    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();
    @Builder.Default
    private List<EnforcementEvidence> evidence = new ArrayList<>();
    @Builder.Default
    private List<EnforcementEvidence> supportingEvidence = new ArrayList<>();
    @Builder.Default
    private List<String> datasetsUsed = new ArrayList<>();
    private ExpectedImpact expectedImpact;
    private String urgency;
    private String actionWindow;
    private double confidence;
    @Builder.Default
    private List<String> limitations = new ArrayList<>();
    private String forecastEngine;
    private String snapshotId;
    private Instant generatedAt;
}
