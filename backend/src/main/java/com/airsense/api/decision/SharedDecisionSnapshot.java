package com.airsense.api.decision;

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
public class SharedDecisionSnapshot {
    private String snapshotId;
    private String searchedLocationKey;
    private Double latitude;
    private Double longitude;
    private String stationKey;
    private String stationLocationKey;
    private String stationName;
    private Integer currentAqi;
    private String currentAqiStandard;
    private String currentProvider;
    private String forecastStandard;
    @Builder.Default
    private Map<String, Object> pollutants = new HashMap<>();
    @Builder.Default
    private Map<String, Object> weather = new HashMap<>();
    private Object observedAt;
    private Instant generatedAt;
}
