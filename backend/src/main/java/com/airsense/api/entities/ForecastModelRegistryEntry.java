package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "forecast_model_registry")
@CompoundIndexes({
        @CompoundIndex(name = "forecast_model_active_idx", def = "{'aqiStandard': 1, 'horizonHours': 1, 'modelScope': 1, 'active': 1, 'promotedAt': -1}"),
        @CompoundIndex(name = "forecast_model_id_idx", def = "{'modelId': 1}", unique = true)
})
public class ForecastModelRegistryEntry {
    @Id
    private String id;
    private String modelId;
    private String aqiStandard;
    private Integer horizonHours;
    private String modelScope;
    private String forecastScope;
    private String stationKey;
    private String modelFamily;
    private String version;
    private String artifactPath;
    private String featureSchemaVersion;
    private Instant trainingStart;
    private Instant trainingEnd;
    private Integer trainingRowCount;
    @Builder.Default
    private List<String> features = List.of();
    @Builder.Default
    private Map<String, Object> hyperparameters = new HashMap<>();
    @Builder.Default
    private Map<String, Object> metrics = new HashMap<>();
    @Builder.Default
    private Map<String, Object> baselineMetrics = new HashMap<>();
    private String promotionStatus;
    private String previousPromotedVersion;
    private Instant promotedAt;
    private Instant activatedAt;
    private Instant deactivatedAt;
    private Boolean rollbackEligible;
    private String rollbackReason;
    private String datasetChecksum;
    private String artifactChecksum;
    private Boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
