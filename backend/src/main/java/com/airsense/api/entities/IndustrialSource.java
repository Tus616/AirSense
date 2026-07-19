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

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "industrial_sources")
@CompoundIndexes({
    @CompoundIndex(name = "ward_category_idx", def = "{'wardId': 1, 'category': 1}")
})
public class IndustrialSource {
    @Id
    private String id;
    
    private String facilityId;
    private String facilityName;
    private String category; // brick-kiln, factory, power-unit, waste-burning, refinery, warehouse
    
    private String wardId;
    
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint location;
    
    private List<String> emissionTypes; // PM2.5, PM10, NO2, SO2, CO
    
    private String riskLevel; // LOW, MEDIUM, HIGH
    private String complianceStatus; // COMPLIANT, WARNING, VIOLATION, UNKNOWN
}
