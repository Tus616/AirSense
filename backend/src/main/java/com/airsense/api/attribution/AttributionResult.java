package com.airsense.api.attribution;

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
public class AttributionResult {
    private String status;
    private String city;
    private String cityId;
    private String wardId;
    @Builder.Default
    private Map<String, Object> location = new HashMap<>();
    @Builder.Default
    private Map<String, Object> currentAqi = new HashMap<>();
    @Builder.Default
    private Map<String, Object> method = new HashMap<>();
    private Instant timestamp;
    private Instant generatedAt;
    private String snapshotId;
    private String locationKey;
    private Object snapshotObservedAt;
    private Object snapshotGeneratedAt;
    private Boolean snapshotReused;
    private String locationHash;
    @Builder.Default
    private List<PollutionSourceContribution> sources = new ArrayList<>();
    private PollutionSourceType dominantSource;
    private double overallConfidence;
    private String overallConfidenceLabel;
    private int unknownContributionPercent;
    private String explanation;
    private String calculationMethod;
    @Builder.Default
    private Map<String, Double> rawScores = new HashMap<>();
    @Builder.Default
    private Map<String, Object> evidenceCoverage = new HashMap<>();
    @Builder.Default
    private Map<String, Object> diagnostics = new HashMap<>();
    @Builder.Default
    private Map<String, String> providerStatus = new HashMap<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
