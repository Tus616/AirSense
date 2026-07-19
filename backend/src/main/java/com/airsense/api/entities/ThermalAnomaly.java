package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "thermal_anomalies")
@CompoundIndexes({
    @CompoundIndex(name = "ward_time_idx", def = "{'wardId': 1, 'timestamp': -1}")
})
public class ThermalAnomaly {
    @Id
    private String id;
    
    private String anomalyId;
    private String wardId;
    private Instant timestamp;
    
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint location;
    
    private double surfaceTemperature;
    private double thresholdTemperature;
    private String intensity; // LOW, MEDIUM, HIGH
    private String sourceSatellite; // Sentinel-5P, MODIS, fixture
}
