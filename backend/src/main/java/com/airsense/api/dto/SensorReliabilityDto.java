package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensorReliabilityDto {
    private String stationId;
    private String name;
    private double uptime;
    private double dataCompleteness;
    private String lastCalibration;
}
