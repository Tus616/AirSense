package com.airsense.api.advisory;

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
public class HealthAdvisoryResult {
    private String city;
    private String cityId;
    private String wardId;
    private Instant generatedAt;
    private String snapshotId;
    private Integer currentAqi;
    private Integer forecastPeakAqi;
    private String dominantSource;
    private AdvisorySeverity overallSeverity;
    private ExposureRisk overallExposureRisk;
    @Builder.Default
    private List<HealthAdvisory> advisories = new ArrayList<>();
    @Builder.Default
    private Map<String, String> providerStatus = new HashMap<>();
}
