package com.airsense.api.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {
    private String riskLevel;
    private Integer currentAqi;
    private Integer peakForecastAqi;
    private String trend;
    private String decisionSummary;
    private double confidence;
    private String dataOrigin;
    @Builder.Default
    private List<String> limitations = new ArrayList<>();
    private String snapshotId;
    private String currentRisk;
    private String forecastRisk;
    private String dominantSourceRisk;
    private String populationExposureRisk;
    private String sensitiveZoneRisk;
    private String overallRiskLevel;
}
