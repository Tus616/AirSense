package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WardForecastResponseDto {
    private String wardId;
    private String sensorId;
    private String generatedAt;
    private int horizonHours;
    private String status;
    private String mode;
    private String reason;
    private List<HourlyForecastDto> hourlyPredictions;
    private List<ForecastDayDto> dailySummary;
    private Map<String, Double> sourceAttribution;
}
