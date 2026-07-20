package com.airsense.api.forecast;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class ModelForecast {
    @Builder.Default
    private Map<Integer, Integer> predictions = new HashMap<>();
    @Builder.Default
    private Map<Integer, String> categories = new HashMap<>();
    private double modelConfidence;
    private String modelVersion;
    private boolean available;
    private boolean fallbackUsed;
    private String unavailableReason;
}
