package com.airsense.api.explainability;

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
public class ReasoningStep {
    private int order;
    private String stage;
    private String statement;
    private double confidence;
    @Builder.Default
    private List<String> datasetsUsed = new ArrayList<>();
}
