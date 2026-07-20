package com.airsense.api.fusion;

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
public class CityEnvironmentalContext {
    @Builder.Default
    private String city = "";
    @Builder.Default
    private String cityId = "";
    @Builder.Default
    private Map<String, Object> coordinates = new HashMap<>();
    @Builder.Default
    private Instant timestamp = Instant.now();
    @Builder.Default
    private Map<String, Object> weather = new HashMap<>();
    @Builder.Default
    private Map<String, Object> aqi = new HashMap<>();
    @Builder.Default
    private Map<String, Object> satellite = new HashMap<>();
    @Builder.Default
    private Map<String, Object> traffic = new HashMap<>();
    @Builder.Default
    private List<Map<String, Object>> industries = new ArrayList<>();
    @Builder.Default
    private List<Map<String, Object>> construction = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> population = new HashMap<>();
    @Builder.Default
    private Map<String, Object> greenCover = new HashMap<>();
    @Builder.Default
    private Map<String, Object> wind = new HashMap<>();
    @Builder.Default
    private double temperature = 0.0;
    @Builder.Default
    private double humidity = 0.0;
    @Builder.Default
    private List<Map<String, Object>> historicalAQI = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> landUse = new HashMap<>();
    @Builder.Default
    private Map<String, Double> confidenceScores = new HashMap<>();
    @Builder.Default
    private Map<String, String> providerStatus = new HashMap<>();
    @Builder.Default
    private Map<String, Long> providerLatency = new HashMap<>();
    @Builder.Default
    private Map<String, Double> providerConfidence = new HashMap<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    public static CityEnvironmentalContext empty(String cityId) {
        return CityEnvironmentalContext.builder()
                .cityId(cityId != null ? cityId : "UNKNOWN_PLACE")
                .timestamp(Instant.now())
                .build();
    }
}
