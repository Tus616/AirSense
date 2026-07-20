package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdvisoryDto {
    private String wardId;
    private String generatedAt;
    private int peakAqi;
    private String category;
    private String primarySource;
    private String municipalDirective;
    private String citizenAdvisory;
    private String status;
}
