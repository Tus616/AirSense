package com.airsense.api.advisory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthAdvisoryEvidence {
    private String dataset;
    private String signal;
    private String description;
    private double confidence;
}
