package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantResponseDto {
    private String answer;
    private String groundingSummary;
    private String safetyNotes;
    private boolean isSimulated;
    private String source;
}
