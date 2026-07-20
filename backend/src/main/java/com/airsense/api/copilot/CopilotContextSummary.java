package com.airsense.api.copilot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotContextSummary {
    private String cityId;
    private String previousIntent;
    private String selectedTimelineFrame;
    private String referencedSource;
    private String referencedAction;
}
