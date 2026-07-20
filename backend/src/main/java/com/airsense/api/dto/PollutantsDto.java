package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollutantsDto {
    private double pm25;
    private double pm10;
    private double no2;
    private double so2;
    private double co;
    private double o3;
}
