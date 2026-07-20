package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "SyntheticPollutionEvents")
public class SyntheticPollutionEvent {
    @Id
    private String id;
    private String eventId;
    private String cityId;
    private String wardId;
    private String gridCellId;
    private Instant timestamp;
    private String trueSource;
    private List<ExpectedSignal> expectedSignals;
    private int injectedAqiImpact;
    private String dataSource;
    private Instant createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpectedSignal {
        private String signalType;
        private String refId;
        private String description;
    }
}
