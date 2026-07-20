package com.airsense.api.forecast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForecastConfidence {
    private double modelConfidence;
    private double inputCompleteness;
    private double providerConfidence;
    private double horizonConfidence;
    private double dataFreshness;
    private double finalConfidence;
}
