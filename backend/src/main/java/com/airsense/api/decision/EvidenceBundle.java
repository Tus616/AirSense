package com.airsense.api.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceBundle {
    @Builder.Default
    private List<String> datasetsUsed = new ArrayList<>();
    @Builder.Default
    private Map<String, Double> confidenceScores = new HashMap<>();
    @Builder.Default
    private Map<String, String> providerStatus = new HashMap<>();
    @Builder.Default
    private List<String> explanations = new ArrayList<>();
}
