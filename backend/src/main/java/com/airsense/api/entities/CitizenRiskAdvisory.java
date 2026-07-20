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

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "CitizenRiskAdvisories")
@CompoundIndexes({
    @CompoundIndex(name = "user_generated_idx", def = "{'userId': 1, 'generatedAt': -1}"),
    @CompoundIndex(name = "city_ward_generated_idx", def = "{'cityId': 1, 'wardId': 1, 'generatedAt': -1}")
})
public class CitizenRiskAdvisory {
    @Id
    private String id;
    
    private String userId; // Optional for public/kiosk feeds
    private String wardId;
    private String cityId;
    
    @Indexed
    private String language;
    
    private String generatedAt;
    
    private int currentAqi;
    private int forecastPeakAqi;
    private String forecastPeakAt;
    
    private double wardRiskScore;
    private double personalRiskScore;
    private String riskLevel; // LOW, MODERATE, HIGH, SEVERE
    
    private Map<String, Object> vulnerabilityFactors;
    
    private String topLine;
    private String advisory;
    private List<String> actions;
    private String ivrScript;
    
    private String source; // gemini | fallback
    
    private String forecastRef;
    private String vulnerabilityRef;
}
