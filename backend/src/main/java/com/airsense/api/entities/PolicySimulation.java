package com.airsense.api.entities;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@Document(collection = "PolicySimulations")
public class PolicySimulation {
    @Id
    private String id;
    
    private String scenario; // e.g., "Implement odd-even scheme for 7 days"
    private String wardId;
    
    private List<AgentResponse> agentDebate;
    private String recommendedAction;
    private String riskTradeoffs;
    
    private String createdBy;
    private Instant createdAt;
    
    private boolean isSimulated; // true if API key was missing
    private String source;

    @Data
    @Builder
    public static class AgentResponse {
        private String agentRole;
        private String perspective;
        private String impactScore; // -10 to +10
    }
}
