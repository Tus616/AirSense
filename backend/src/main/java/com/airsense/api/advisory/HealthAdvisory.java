package com.airsense.api.advisory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthAdvisory {
    private String advisoryId;
    private AdvisoryTargetGroup targetGroup;
    private AdvisorySeverity severity;
    private String title;
    private String message;
    @Builder.Default
    private List<String> recommendedActions = new ArrayList<>();
    @Builder.Default
    private List<String> avoidActivities = new ArrayList<>();
    private ExposureRisk exposureRisk;
    private Instant validFrom;
    private Instant validUntil;
    private double confidence;
    private Instant generatedAt;
    @Builder.Default
    private List<HealthAdvisoryEvidence> evidence = new ArrayList<>();
}
