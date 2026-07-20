package com.airsense.api.temporal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEvent {
    private String eventId;
    private String eventType;
    private String title;
    private String description;
    private Instant timestamp;
    private String severity;
    private double confidence;
}
