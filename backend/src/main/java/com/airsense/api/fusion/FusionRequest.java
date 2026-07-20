package com.airsense.api.fusion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FusionRequest {
    @Builder.Default
    private String cityId = "UNKNOWN_PLACE";
    private String placeId;
    private String cityName;
    private String state;
    private String country;
    private Double latitude;
    private Double longitude;
    @Builder.Default
    private Map<String, Object> parameters = Map.of();

    public static FusionRequest forCity(String cityId) {
        return FusionRequest.builder()
                .cityId(cityId == null || cityId.isBlank() ? "UNKNOWN_PLACE" : cityId)
                .build();
    }

    public FusionRequest normalized() {
        if (cityId == null || cityId.isBlank()) {
            cityId = "UNKNOWN_PLACE";
        }
        if (parameters == null) {
            parameters = Map.of();
        }
        return this;
    }

    public String cacheKey() {
        return String.join(":",
                cityId,
                placeId != null ? placeId : "",
                cityName != null ? cityName : "",
                state != null ? state : "",
                country != null ? country : "",
                SnapshotIdentity.normalizedLatitude(latitude),
                SnapshotIdentity.normalizedLongitude(longitude));
    }
}
