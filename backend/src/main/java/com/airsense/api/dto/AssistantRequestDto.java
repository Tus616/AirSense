package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantRequestDto {
    private String question;
    private String wardId; // optional
    private boolean isVulnerable; // context toggle
}
