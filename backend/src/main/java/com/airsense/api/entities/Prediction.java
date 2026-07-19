package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "Predictions")
@CompoundIndexes({
    @CompoundIndex(name = "ward_generated_idx", def = "{'wardId': 1, 'generatedAt': -1}")
})
public class Prediction {
    @Id
    private String id;
    private String cityId;
    private String cityName;
    private String wardId;
    private String sensorId;
    private String generatedAt;
    private int horizonHours;
    private List<HourlyPrediction> predictions;
    private String modelVersion;
    private Object featureImportanceSummary;
    private Double seasonalMultiplier;
    private Boolean fallbackUsed;
    private String status;
    private String explanation;
    
    private Double rmse;
    private Double baselineRmse;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourlyPrediction {
        private String timestamp;
        private Integer predictedAqi;
        private double predictedPm25;
        private String category;
    }
}
