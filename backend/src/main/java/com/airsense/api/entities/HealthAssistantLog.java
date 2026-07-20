package com.airsense.api.entities;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@Document(collection = "HealthAssistantLogs")
public class HealthAssistantLog {
    @Id
    private String id;
    
    private String wardId;
    private String userId; // Optional, if we tie queries to logged-in users
    
    private String question;
    private String answer;
    
    private String groundingUsed; // summary of data used to answer
    private boolean simulatedFallback; // true if API key was missing
    
    @Indexed
    private Instant createdAt;
}
