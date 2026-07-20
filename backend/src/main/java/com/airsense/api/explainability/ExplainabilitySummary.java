package com.airsense.api.explainability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplainabilitySummary {
    private double explainabilityScore;
    private double overallConfidence;
    private int evidenceCount;
    private int reasoningStepCount;
    private boolean degradedMode;
}
