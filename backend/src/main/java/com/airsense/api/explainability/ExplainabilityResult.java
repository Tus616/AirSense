package com.airsense.api.explainability;

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
public class ExplainabilityResult {
    private String decisionId;
    private String city;
    private String cityId;
    private Instant generatedAt;
    private String snapshotId;
    private String locationHash;
    private double overallConfidence;
    private ConfidenceBreakdown confidenceBreakdown;
    @Builder.Default
    private List<ReasoningStep> reasoning = new ArrayList<>();
    @Builder.Default
    private List<EvidenceItem> evidence = new ArrayList<>();
    @Builder.Default
    private List<String> datasets = new ArrayList<>();
    @Builder.Default
    private Map<String, String> providerStatus = new HashMap<>();
    @Builder.Default
    private List<String> limitations = new ArrayList<>();
    private String explanation;
    private double explainabilityScore;
    @Builder.Default
    private List<ModelExplanation> modelExplanations = new ArrayList<>();
}
