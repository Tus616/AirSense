package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "forecast_retraining_requests")
@CompoundIndex(name = "retrain_active_request_idx", def = "{'stationKey': 1, 'modelScope': 1, 'horizonHours': 1, 'status': 1}")
public class ForecastRetrainingRequest {
    @Id
    private String id;
    private String stationKey;
    private String modelScope;
    private Integer horizonHours;
    private String reason;
    @Builder.Default
    private Map<String, Object> metrics = new HashMap<>();
    private Integer sampleSize;
    private Instant requestedAt;
    private String status;
}
