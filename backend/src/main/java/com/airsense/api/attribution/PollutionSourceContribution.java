package com.airsense.api.attribution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollutionSourceContribution {
    private PollutionSourceType sourceType;
    private String source;
    private String displayName;
    private int contributionPercent;
    private Integer percentage;
    private Integer estimatedContributionPercent;
    private double confidence;
    private String confidenceLabel;
    private double rawScore;
    private int positiveEvidenceCount;
    private int contradictingEvidenceCount;
    private String geometrySource;
    private String signalType;
    private String dataOrigin;
    private String dataAvailability;
    private String dataFreshness;
    private String calculationMethod;
    private String limitations;
    private String timestamp;
    @Builder.Default
    private Map<String, Object> sourceZone = Map.of();
    @Builder.Default
    private List<AttributionEvidence> evidence = new ArrayList<>();
    @Builder.Default
    private List<AttributionEvidence> supportingEvidence = new ArrayList<>();
    @Builder.Default
    private List<AttributionEvidence> contradictingEvidence = new ArrayList<>();
    @Builder.Default
    private List<String> missingEvidence = new ArrayList<>();
    @Builder.Default
    private List<String> datasetsUsed = new ArrayList<>();
}
