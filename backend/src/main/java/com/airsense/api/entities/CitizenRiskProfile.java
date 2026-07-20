package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "citizen_risk_profiles")
public class CitizenRiskProfile {
    @Id
    private String id;
    
    @Indexed(unique = true)
    private String userId; // maps to User.id or User.email
    
    private String wardId;
    private String gridId;
    
    private List<String> vulnerabilities; // e.g. ASTHMA, COPD, ELDERLY, CHILD, PREGNANCY, OUTDOOR_WORKER
    private String language; // "en" or "hi"
    private List<String> channels; // "IN_APP", "SMS", "EMAIL", "PUSH"
}
