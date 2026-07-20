package com.airsense.api.enforcement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpectedImpact {
    private String impactLevel;
    private int estimatedAqiReduction;
    private String exposureReduction;
    private String timeframe;
    private String rationale;
}
