package com.airsense.api.attribution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollutionAttributionInput {
    @Builder.Default
    private Map<String, Object> location = new HashMap<>();
    @Builder.Default
    private Map<String, Object> currentAqi = new HashMap<>();
    @Builder.Default
    private Map<String, Object> pollutants = new HashMap<>();
    @Builder.Default
    private Map<String, Object> weather = new HashMap<>();
    @Builder.Default
    private Map<String, Object> temporal = new HashMap<>();
    @Builder.Default
    private Map<String, Object> geospatial = new HashMap<>();
    @Builder.Default
    private Map<String, Object> fireEvidence = new HashMap<>();
    @Builder.Default
    private Map<String, Object> satelliteEvidence = new HashMap<>();
    @Builder.Default
    private Map<String, Object> historicalContext = new HashMap<>();
    @Builder.Default
    private Map<String, Object> providerStatus = new HashMap<>();
    private String snapshotId;
    private String locationKey;
    private Object snapshotObservedAt;
    private Object snapshotGeneratedAt;
    private Boolean snapshotReused;
    private String locationHash;
    @Builder.Default
    private Instant generatedAt = Instant.now();
}
