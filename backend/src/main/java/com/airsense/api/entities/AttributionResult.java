package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "attribution_results")
@CompoundIndexes({
    @CompoundIndex(name = "ward_time_idx", def = "{'wardId': 1, 'timestamp': -1}")
})
public class AttributionResult {
    @Id
    private String id;
    
    private String cityId;
    private String cityName;
    
    private String wardId;
    private Instant timestamp;
    private int aqi;
    private boolean elevated;
    
    private List<RankedSource> rankedSources;
    private Map<String, Double> featureImportanceWeights;
    private String dispersionContext;
    private String modelVersion;
    
    @Indexed
    private Instant generatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankedSource {
        @Indexed
        private String category; // traffic, construction, industrial, thermal-burning, wind-transport, mixed
        private double confidence; // 0-100
        private double rawScore;
        private List<ContributingSignal> contributingSignals;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContributingSignal {
        private String signalType;
        private String strength;
        private double weight;
        private String evidenceRef;
        private String summary;
    }
}
