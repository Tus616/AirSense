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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "citizen_notifications")
@CompoundIndexes({
    @CompoundIndex(name = "user_time_idx", def = "{'userId': 1, 'generatedAt': -1}")
})
public class CitizenNotification {
    @Id
    private String id;
    
    @Indexed
    private String userId; // maps to User.id or email
    
    private String notificationId;
    private Instant generatedAt;
    
    private String language;
    private String channel; // "IN_APP", "SMS", "EMAIL", "PUSH"
    private String status; // "SENT", "FAILED", "DISABLED"
    
    private String message;
    private String riskCategory; // "LOW", "MODERATE", "HIGH", "SEVERE"
    private String forecastRef; // Prediction ID
}
