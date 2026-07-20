package com.airsense.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceSearchResultDto {
    private String placeId;
    private String displayName;
    private String cityName;
    private String state;
    private String country;
    private Double latitude;
    private Double longitude;
    private String type;
    private Double importance;
}
