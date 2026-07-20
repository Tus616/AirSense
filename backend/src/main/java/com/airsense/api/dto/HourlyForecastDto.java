package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HourlyForecastDto {
    private String timestamp;
    private Integer predictedAqi;
    private Double predictedPm25;
    private String category;
}
