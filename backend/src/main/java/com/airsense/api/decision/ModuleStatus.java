package com.airsense.api.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModuleStatus {
    private String status;
    private String dataOrigin;
    private double confidence;
    private String reason;
    @Builder.Default
    private List<String> limitations = new ArrayList<>();
    @Builder.Default
    private List<String> missingInputs = new ArrayList<>();
    private Instant generatedAt;
    private String snapshotId;
}
