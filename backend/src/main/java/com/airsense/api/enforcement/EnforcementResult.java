package com.airsense.api.enforcement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnforcementResult {
    private String city;
    private String cityId;
    private String wardId;
    private Instant generatedAt;
    private Integer currentAqi;
    private Integer forecastPeakAqi;
    private String dominantSource;
    private String snapshotId;
    private String locationHash;
    private String locationKey;
    private Object snapshotObservedAt;
    private Object snapshotGeneratedAt;
    private Boolean snapshotReused;
    @Builder.Default
    private List<EnforcementRecommendation> recommendations = new ArrayList<>();
    @Builder.Default
    private Map<String, String> providerStatus = new HashMap<>();
}
