package com.airsense.api.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "grid_forecasts")
@CompoundIndexes({
    @CompoundIndex(name = "grid_generated_idx", def = "{'gridId': 1, 'generatedAt': -1}"),
    @CompoundIndex(name = "city_generated_idx", def = "{'cityId': 1, 'generatedAt': -1}")
})
public class GridForecast {
    @Id
    private String id;
    private String gridId;
    private String wardId;
    private String cityId;
    private Instant generatedAt;
    private int horizonHours;
    
    // Add simple geospatial coords for the grid
    @GeoSpatialIndexed
    private double[] location; // [longitude, latitude]

    private List<HourlyPrediction> predictions;
    private Map<String, Object> dispersionInputs;
    private String modelVersion;
    private Map<String, Object> metrics; // rmse, persistenceRmse, improvementPct
    private Map<String, Double> featureImportanceSummary;

    // Phase 10 Post-Processing Metadata
    private Map<String, Object> interpolationMetadata;
    private Map<String, Object> dispersionMetadata;
    private Map<String, Object> seasonalMetadata;

    // Constructors, Getters, Setters
    public GridForecast() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getGridId() { return gridId; }
    public void setGridId(String gridId) { this.gridId = gridId; }

    public String getWardId() { return wardId; }
    public void setWardId(String wardId) { this.wardId = wardId; }

    public String getCityId() { return cityId; }
    public void setCityId(String cityId) { this.cityId = cityId; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public int getHorizonHours() { return horizonHours; }
    public void setHorizonHours(int horizonHours) { this.horizonHours = horizonHours; }

    public double[] getLocation() { return location; }
    public void setLocation(double[] location) { this.location = location; }

    public List<HourlyPrediction> getPredictions() { return predictions; }
    public void setPredictions(List<HourlyPrediction> predictions) { this.predictions = predictions; }

    public Map<String, Object> getDispersionInputs() { return dispersionInputs; }
    public void setDispersionInputs(Map<String, Object> dispersionInputs) { this.dispersionInputs = dispersionInputs; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }

    public Map<String, Double> getFeatureImportanceSummary() { return featureImportanceSummary; }
    public void setFeatureImportanceSummary(Map<String, Double> featureImportanceSummary) { this.featureImportanceSummary = featureImportanceSummary; }

    public Map<String, Object> getInterpolationMetadata() { return interpolationMetadata; }
    public void setInterpolationMetadata(Map<String, Object> interpolationMetadata) { this.interpolationMetadata = interpolationMetadata; }

    public Map<String, Object> getDispersionMetadata() { return dispersionMetadata; }
    public void setDispersionMetadata(Map<String, Object> dispersionMetadata) { this.dispersionMetadata = dispersionMetadata; }

    public Map<String, Object> getSeasonalMetadata() { return seasonalMetadata; }
    public void setSeasonalMetadata(Map<String, Object> seasonalMetadata) { this.seasonalMetadata = seasonalMetadata; }

    public static class HourlyPrediction {
        private Instant timestamp;
        private int predictedAqi;
        private double predictedPm25;
        private String category;
        private double confidence;

        public HourlyPrediction() {}

        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

        public int getPredictedAqi() { return predictedAqi; }
        public void setPredictedAqi(int predictedAqi) { this.predictedAqi = predictedAqi; }

        public double getPredictedPm25() { return predictedPm25; }
        public void setPredictedPm25(double predictedPm25) { this.predictedPm25 = predictedPm25; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }
}
