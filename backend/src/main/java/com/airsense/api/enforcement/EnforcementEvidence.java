package com.airsense.api.enforcement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnforcementEvidence {
    private String dataset;
    private String signal;
    private String description;
    private double confidence;
}
