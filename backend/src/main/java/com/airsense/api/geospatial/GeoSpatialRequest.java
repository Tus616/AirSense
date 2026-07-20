package com.airsense.api.geospatial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoSpatialRequest {
    @Builder.Default
    private String cityId = "UNKNOWN_PLACE";
    private String placeId;
    private String cityName;
    private String state;
    private String country;
    private String wardId;
    private Double latitude;
    private Double longitude;

    public GeoSpatialRequest normalized() {
        if (cityId == null || cityId.isBlank()) {
            cityId = "UNKNOWN_PLACE";
        }
        return this;
    }

    public String cacheKey() {
        return cityId + ":" + (wardId != null ? wardId : "") + ":" + (latitude != null ? latitude : "") + ":" + (longitude != null ? longitude : "");
    }
}
