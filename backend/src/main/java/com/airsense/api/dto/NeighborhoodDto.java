package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NeighborhoodDto {
    private String id;
    private String name;
    private Integer aqi;
    private String category;
    private String dominantPollutant;
}
