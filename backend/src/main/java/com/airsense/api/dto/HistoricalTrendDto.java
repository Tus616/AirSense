package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalTrendDto {
    private String date;
    private int aqi;
    private double pm25;
    private double pm10;
    private double no2;
    private double o3;
}
