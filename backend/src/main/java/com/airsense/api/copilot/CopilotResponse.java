package com.airsense.api.copilot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotResponse {
    private String cityId;
    private String question;
    private CopilotIntent intent;
    private String answer;
    private String status;
    private String mode;
    private double confidence;
    private boolean degradedMode;
    private Instant generatedAt;
    private String snapshotId;
    private String locationHash;
    private String locationKey;
    private Object snapshotObservedAt;
    private Object snapshotGeneratedAt;
    private Boolean snapshotReused;
    @Builder.Default
    private List<CopilotCitation> citations = new ArrayList<>();
    @Builder.Default
    private List<CopilotEvidence> evidence = new ArrayList<>();
    @Builder.Default
    private List<String> limitations = new ArrayList<>();
    @Builder.Default
    private List<CopilotSuggestedQuestion> suggestedQuestions = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> grounding = new LinkedHashMap<>();
    private CopilotContextSummary contextSummary;
}
