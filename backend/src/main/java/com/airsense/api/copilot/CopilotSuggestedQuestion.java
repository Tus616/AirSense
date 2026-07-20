package com.airsense.api.copilot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotSuggestedQuestion {
    private String label;
    private String question;
    private CopilotIntent intent;
}
