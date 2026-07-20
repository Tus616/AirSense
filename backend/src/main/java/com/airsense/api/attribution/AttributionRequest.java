package com.airsense.api.attribution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionRequest {
    @Builder.Default
    private String cityId = "UNKNOWN_PLACE";
    private String placeId;
    private String cityName;
    private String state;
    private String country;
    private String wardId;
    private Double latitude;
    private Double longitude;
    private boolean refreshEvidence;

    public AttributionRequest normalized() {
        if (cityId == null || cityId.isBlank()) {
            cityId = "UNKNOWN_PLACE";
        }
        return this;
    }
}
