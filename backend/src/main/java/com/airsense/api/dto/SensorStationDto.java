package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensorStationDto {
    private String id;
    private String name;
    private double lat;
    private double lng;
    private int aqi;
    private String status;
    private PollutantsDto pollutants;
}
