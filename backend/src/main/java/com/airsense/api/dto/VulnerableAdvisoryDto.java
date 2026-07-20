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
public class VulnerableAdvisoryDto {
    private String headline;
    private String body;
    private List<String> recommendations;
}
