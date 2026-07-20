package com.airsense.api.attribution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionEvidence {
    private String type;
    private String message;
    private String dataset;
    private String provider;
    private String signal;
    private Object value;
    private String unit;
    private double weight;
    private String signalType;
    private String geometrySource;
    private String limitation;
    private String direction;
    private String dataOrigin;
    private String timestamp;
}
