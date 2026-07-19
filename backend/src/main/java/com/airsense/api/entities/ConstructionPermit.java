package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
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
@Document(collection = "construction_permits")
@CompoundIndexes({
    @CompoundIndex(name = "ward_time_idx", def = "{'wardId': 1, 'activeFrom': 1, 'activeTo': 1}")
})
public class ConstructionPermit {
    @Id
    private String id;
    
    private String permitId;
    private String wardId;
    
    private String projectType; // roadwork, building construction, demolition, metro work, utility digging
    private Instant activeFrom;
    private Instant activeTo;
    
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPolygon polygon;
    
    private String contractor;
    private String dustRiskLevel; // LOW, MEDIUM, HIGH
    private String status; // ACTIVE, EXPIRED, SUSPENDED
}
