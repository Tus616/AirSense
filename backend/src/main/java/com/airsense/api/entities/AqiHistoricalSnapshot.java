package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "aqi_historical_snapshots")
@CompoundIndexes({
        @CompoundIndex(name = "observation_unique_idx", def = "{'locationKey': 1, 'provider': 1, 'aqiStandard': 1, 'providerObservedAt': 1}", unique = true),
        @CompoundIndex(name = "location_time_idx", def = "{'locationKey': 1, 'providerObservedAt': -1}"),
        @CompoundIndex(name = "location_standard_time_idx", def = "{'locationKey': 1, 'aqiStandard': 1, 'providerObservedAt': -1}")
})
public class AqiHistoricalSnapshot {
    @Id
    private String id;
    @Indexed
    private String locationKey;
    private String locationKeyVersion;
    private String canonicalLocationKey;
    @Builder.Default
    private List<String> legacyLocationKeys = new ArrayList<>();
    private String searchedDisplayName;
    private String city;
    private String state;
    private String country;
    private Double latitude;
    private Double longitude;

    private Integer currentAqi;
    private String aqiStandard;
    private String aqiCategory;
    private String primaryPollutant;

    private String provider;
    private Boolean isFallback;
    @Indexed
    private String stationKey;
    @Indexed
    private String stationLocationKey;
    private String stationName;
    private Double stationLatitude;
    private Double stationLongitude;
    private Double stationDistanceKm;
    private String providerReturnedCity;
    private String providerReturnedStation;

    private Double pm25;
    private Double pm10;
    private Double no2;
    private Double so2;
    private Double co;
    private Double o3;
    private Double nh3;

    private Double temperatureCelsius;
    private Double humidityPercent;
    private Double pressureHpa;
    private Double windSpeedMps;
    private Double windDirectionDegrees;
    private Double rainfallMm;
    private Double cloudCoverPercent;
    private Double visibilityMeters;

    private Integer openWeatherAqiIndex;
    private String openWeatherAqiScale;

    private Instant providerObservedAt;
    private Instant weatherObservedAt;
    private Instant ingestedAt;

    private String dataOrigin;
    private String dataQualityStatus;
    @Builder.Default
    private List<String> dataQualityWarnings = new ArrayList<>();
}
