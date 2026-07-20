package com.airsense.api.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {
    private String currentRisk;
    private String forecastRisk;
    private String dominantSourceRisk;
    private String populationExposureRisk;
    private String sensitiveZoneRisk;
    private String overallRiskLevel;
}
