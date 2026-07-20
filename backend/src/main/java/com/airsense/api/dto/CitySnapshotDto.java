package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitySnapshotDto {
    private String cityId;
    private String cityName;
    private boolean isSynthetic;
    private Integer currentAqi;
    private Integer averageAqi30d;
    private List<Integer> aqiTrend;
    private Integer forecastPeakAqi;
    private String dominantSource;
    private Double forecastRmse;
    private Double baselineRmse;
    private int openEnforcementCount;
    private int resolvedEnforcementCount;
    private double avgResponseTimeHours;
    private int advisoryCount;
}
