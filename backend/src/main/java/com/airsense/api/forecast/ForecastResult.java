package com.airsense.api.forecast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForecastResult {
    private String city;
    private String cityId;
    private String wardId;
    private Instant generatedAt;
    private String snapshotId;
    private String locationHash;
    private Map<String, Object> location;
    private Integer currentAqi;
    private String forecastStandard;
    private String currentProvider;
    private String engine;
    private String stationKey;
    private String stationName;
    private String stationLocationKey;
    private int dataWindowHours;
    private int observationCount;
    private String dataQuality;
    @Builder.Default
    private Map<String, ForecastPoint> forecast = new HashMap<>();
    @Builder.Default
    private Map<String, ForecastPoint> baseline = new HashMap<>();
    private double overallConfidence;
    private String overallTrend;
    private boolean fallbackUsed;
    private String modelVersion;
    private String mode;
    private String formulaVersion;
    @Builder.Default
    private Map<String, String> providerStatus = new HashMap<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    public List<ForecastPoint> getForecasts() {
        return forecast == null
                ? List.of()
                : forecast.values().stream()
                        .sorted(Comparator.comparingInt(ForecastPoint::getHorizonHours))
                        .toList();
    }
}
