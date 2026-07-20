package com.airsense.api.copilot;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotRequest {
    @Builder.Default
    private String cityId = "UNKNOWN_PLACE";
    private String placeId;
    private String cityName;
    private String state;
    private String country;
    private Double latitude;
    private Double longitude;
    private String question;
    private String timelineFrame;
    private String conversationId;
    private String snapshotId;
}
