package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "tracked_air_quality_locations")
public class TrackedAirQualityLocation {
    @Id
    private String id;
    @Indexed(unique = true)
    private String locationKey;
    private String locationKeyVersion;
    private String canonicalLocationKey;
    private String stationKey;
    private String stationLocationKey;
    private String stationName;
    private String provider;
    private String sourceType;
    private String displayName;
    private String city;
    private String state;
    private String country;
    private Double latitude;
    private Double longitude;
    private Instant lastSearchedAt;
    private Instant lastIngestedAt;
    private Boolean trackingEnabled;
    private Instant createdAt;
    private Instant updatedAt;
}
