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
public class ModelExplanation {
    private String modelName;
    private String outputType;
    @Builder.Default
    private List<String> inputSignals = new ArrayList<>();
    @Builder.Default
    private List<String> rulesFired = new ArrayList<>();
    private double confidence;
    private String explanation;
}
