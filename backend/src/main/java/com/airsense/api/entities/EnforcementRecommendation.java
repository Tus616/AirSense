package com.airsense.api.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "enforcement_recommendations")
@CompoundIndexes({
    @CompoundIndex(name = "city_status_idx", def = "{'cityId': 1, 'status': 1}"),
    @CompoundIndex(name = "ward_generated_idx", def = "{'wardId': 1, 'generatedAt': -1}"),
    @CompoundIndex(name = "priority_score_idx", def = "{'priorityScore': -1}")
})
public class EnforcementRecommendation {
    @Id
    private String id;
    private String recommendationId;
    private String cityId;
    private String cityName;
    private String wardId;
    private String gridId;
    private Instant generatedAt;
    private int rank;
    private String actionType;
    private String target;
    private double priorityScore;
    private String expectedImpact;
    private double confidence;
    private List<EvidenceItem> evidence;
    private String sourceAttributionRef;
    private String forecastRef;
    private Status status;
    private Map<String, Object> responseTimeMetrics;

    public enum Status {
        OPEN, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, DISMISSED
    }

    public static class EvidenceItem {
        private String type; // e.g., "SENSOR", "SATELLITE", "TRAFFIC", "POLLUTER"
        private String refId;
        private String summary;

        public EvidenceItem() {}

        public EvidenceItem(String type, String refId, String summary) {
            this.type = type;
            this.refId = refId;
            this.summary = summary;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getRefId() { return refId; }
        public void setRefId(String refId) { this.refId = refId; }
        
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
    }

    // Getters and Setters
    public EnforcementRecommendation() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getRecommendationId() { return recommendationId; }
    public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }

    public String getCityId() { return cityId; }
    public void setCityId(String cityId) { this.cityId = cityId; }

    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }

    public String getWardId() { return wardId; }
    public void setWardId(String wardId) { this.wardId = wardId; }

    public String getGridId() { return gridId; }
    public void setGridId(String gridId) { this.gridId = gridId; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public double getPriorityScore() { return priorityScore; }
    public void setPriorityScore(double priorityScore) { this.priorityScore = priorityScore; }

    public String getExpectedImpact() { return expectedImpact; }
    public void setExpectedImpact(String expectedImpact) { this.expectedImpact = expectedImpact; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public List<EvidenceItem> getEvidence() { return evidence; }
    public void setEvidence(List<EvidenceItem> evidence) { this.evidence = evidence; }

    public String getSourceAttributionRef() { return sourceAttributionRef; }
    public void setSourceAttributionRef(String sourceAttributionRef) { this.sourceAttributionRef = sourceAttributionRef; }

    public String getForecastRef() { return forecastRef; }
    public void setForecastRef(String forecastRef) { this.forecastRef = forecastRef; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Map<String, Object> getResponseTimeMetrics() { return responseTimeMetrics; }
    public void setResponseTimeMetrics(Map<String, Object> responseTimeMetrics) { this.responseTimeMetrics = responseTimeMetrics; }
}
