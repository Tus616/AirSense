package com.airsense.api.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineStatus {
    private String fusionStatus;
    private String attributionStatus;
    private String forecastStatus;
    private String providerForecastStatus;
    private int forecastHorizonCount;
    private int locallyPromotedModelHorizonCount;
    private int persistenceFallbackHorizonCount;
    private String optionalAiModelStatus;
    private String enforcementStatus;
    private String advisoryStatus;
    private boolean degradedMode;
    @Builder.Default
    private Map<String, String> failureReasons = new HashMap<>();
}
