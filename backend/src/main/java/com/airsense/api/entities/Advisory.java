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

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "Advisories")
@CompoundIndexes({
    @CompoundIndex(name = "ward_generated_idx", def = "{'wardId': 1, 'generatedAt': -1}")
})
public class Advisory {
    @Id
    private String id;
    private String cityId;
    private String cityName;
    private String wardId;
    
    @Indexed(unique = true)
    private String predictionId; // Link back to the Prediction that triggered this
    
    @Indexed
    private String generatedAt;
    
    private int forecastWindowHours; // usually 48
    private int peakAqi;
    private String category;
    private String primarySource;
    private Object weatherSnapshot; // snapshot of the weather from SensorData
    
    private String municipalDirective;
    private String citizenAdvisory;
    
    private String model;
    private String promptHash;
    private Object sourceMetricRefs;
    
    private String status; // GENERATED, SKIPPED, FAILED
    private String errorMessage;
}
