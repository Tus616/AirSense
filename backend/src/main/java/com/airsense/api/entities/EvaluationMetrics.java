package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "EvaluationMetrics")
public class EvaluationMetrics {
    @Id
    private String id;
    private Instant generatedAt;
    
    private AttributionMetrics attribution;
    private ForecastMetrics forecast;
    private EnforcementMetrics enforcement;
    private AdvisoryMetrics advisory;
    private ResponseTimeMetrics responseTime;
    
    private String dataSource;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttributionMetrics {
        private double precision;
        private double recall;
        private double f1;
        private double accuracy;
        private int evaluatedCount;
        private Map<String, Integer> confusion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForecastMetrics {
        private double modelRmse;
        private double modelMae;
        private double persistenceRmse;
        private double persistenceMae;
        private double improvementPct;
        private int evaluatedCellHours;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EnforcementMetrics {
        private double averageQualityScore;
        private int evaluatedActionCount;
        private int passedActionCount;
        private int failedActionCount;
        private Map<String, Integer> commonFailureReasons;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdvisoryMetrics {
        private int languageCoverageCount;
        private double languageCoveragePercent;
        private double relevanceRate;
        private int totalAdvisories;
        private int relevantAdvisoryCount;
        private int missingForecastReferenceCount;
        private int missingRiskCategoryCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseTimeMetrics {
        private long averageMs;
        private long worstCaseMs;
        private long bestCaseMs;
        private int evaluatedCount;
    }
}
