package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForecastDayDto {
    private String date;
    private Integer aqi;
    private String category;
    private Integer high;
    private Integer low;
    private String advisory;
}
