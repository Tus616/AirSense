package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CityAqiSummaryDto {
    private String city;
    private Integer aqi;
    private String category;
    private String dominantPollutant;
    private String updatedAt;
}
