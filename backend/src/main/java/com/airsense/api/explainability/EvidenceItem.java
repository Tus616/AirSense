package com.airsense.api.explainability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceItem {
    private String dataset;
    private String signal;
    private double weight;
    private double confidence;
    private String provider;
    private Instant timestamp;
}
