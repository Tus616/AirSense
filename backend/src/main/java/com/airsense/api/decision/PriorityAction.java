package com.airsense.api.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriorityAction {
    private String sourceEngine;
    private String actionType;
    private String targetAudience;
    private String responsibleParty;
    private String message;
    private String urgency;
    private int priorityScore;
    private double confidence;
}
