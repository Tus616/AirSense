package com.airsense.api.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "cities")
public class City {
    @Id
    private String id;
    
    @Indexed(unique = true)
    private String cityId;
    
    private String cityName;
    private String state;
    private String country;
    private String timezone;
    private Map<String, List<Double>> boundingBox;
    
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint center;
    
    private long population;
    private List<String> defaultWards;
    private boolean isHomeCity;
    private String dataSource;
}
