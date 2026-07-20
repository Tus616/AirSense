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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "aqi_forecast_runs")
@CompoundIndexes({
        @CompoundIndex(name = "forecast_location_target_idx", def = "{'locationKey': 1, 'forecastStandard': 1, 'targetTime': -1}"),
        @CompoundIndex(name = "forecast_metrics_idx", def = "{'locationKey': 1, 'forecastStandard': 1, 'horizonHours': 1, 'engine': 1, 'evaluated': 1}")
})
public class AqiForecastRun {
    @Id
    private String id;
    private String forecastRunId;
    private String locationKey;
    private String snapshotId;
    private String stationKey;
    private String stationLocationKey;
    private Instant generatedAt;
    private Instant targetTime;
    private Integer horizonHours;
    private Integer predictedAqi;
    private Integer lowerBound;
    private Integer upperBound;
    private String forecastStandard;
    private String engine;
    private String modelVersion;
    private Integer baselinePredictedAqi;
    private Integer actualAqi;
    private Instant actualObservedAt;
    private Double error;
    private Double absoluteError;
    private Double squaredError;
    private Boolean evaluated;
}
