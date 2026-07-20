package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustrialPolluterDto {
    private String id;
    private String name;
    private double lat;
    private double lng;
    private String type;
    private String complianceStatus;
    private String emissionLevel;
}
