package com.airsense.api.copilot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotEvidence {
    private String sourceType;
    private String signal;
    private String value;
    private double confidence;
    private String explanation;
}
