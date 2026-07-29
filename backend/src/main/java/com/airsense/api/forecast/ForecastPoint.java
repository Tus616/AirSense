package com.airsense.api.forecast;

import com.airsense.api.attribution.PollutionSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForecastPoint {
    private int horizonHours;
    private Instant forecastAt;
    private Instant generatedAt;
    private Instant targetTime;
    private Integer predictedAqi;
    private Double predictedDelta;
    private Double unclampedPredictedAqi;
    private Integer lowerBound;
    private Integer upperBound;
    private String aqiCategory;
    private double confidence;
    private String trend;
    private String modelVersion;
    private String mode;
    private String engine;
    private String modelPromotionStatus;
    private String modelFamily;
    private Integer baselinePredictedAqi;
    private Double validationRmse;
    private Double baselineRmse;
    private String fallbackReason;
    private String oodStatus;
    private Double oodScore;
    private String oodLevel;
    @Builder.Default
    private List<String> oodFeatures = List.of();
    @Builder.Default
    private List<String> modelWarnings = List.of();
    @Builder.Default
    private Map<String, Object> trainingDeltaPercentiles = new HashMap<>();
    @Builder.Default
    private Map<String, Object> featureDiagnostics = new HashMap<>();
    @Builder.Default
    private Map<String, Object> modelContributions = new HashMap<>();
    private int historyCount;
    private boolean sufficientHistory;
    private int validObservationCount;
    private double coverageHours;
    private int requiredObservationCount;
    private double requiredCoverageHours;
    @Builder.Default
    private List<String> insufficiencyReasons = List.of();
    @Builder.Default
    private List<String> confidenceReductionReasons = List.of();
    private String confidenceLabel;
    private String dataOrigin;
    @Builder.Default
    private Map<String, Double> featureContributions = new HashMap<>();
    private String formulaVersion;
    @Builder.Default
    private List<String> limitations = List.of();
    private String dataWindow;
    private boolean fallbackUsed;
    private PollutionSourceType expectedDominantSource;
    private String meteorologicalInfluence;
    private String healthRiskLevel;
    private String forecastScope = "STATION";
    private String stationName;
    private String stationKey;
    private String stationLocationKey;
    private String snapshotId;
    private String aqiStandard;
    private String provider;
    private String promotionStatus;
    private int historyObservationCount;
    private double historyCoverageHours;
    private double featureCoveragePercent;
    private ForecastExplanation explanation;
    private ForecastConfidence confidenceBreakdown;
    @Builder.Default
    private List<Map<String, String>> drivers = List.of();
}
