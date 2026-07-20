package com.airsense.api.copilot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotCitation {
    private String sourceType;
    private String label;
    private String value;
    private double confidence;
}
