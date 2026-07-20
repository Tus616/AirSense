package com.airsense.api.explainability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfidenceBreakdown {
    private double weather;
    private double aqi;
    private double satellite;
    private double forecast;
    private double attribution;
    private double enforcement;
    private double citizenAdvisory;
    private double overall;
}
