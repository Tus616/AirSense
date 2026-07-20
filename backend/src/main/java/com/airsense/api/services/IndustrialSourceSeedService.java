package com.airsense.api.services;

import com.airsense.api.entities.IndustrialSource;
import com.airsense.api.repositories.IndustrialSourceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class IndustrialSourceSeedService {

    @Autowired
    private IndustrialSourceRepository repository;

    public void seedData() {
        if (repository.count() == 0) {
            List<IndustrialSource> sources = Arrays.asList(
                createSource("DELHI", "factory", "HIGH", "VIOLATION", Arrays.asList("PM2.5", "SO2")),
                createSource("DELHI", "brick-kiln", "MEDIUM", "UNKNOWN", Arrays.asList("PM10", "CO")),
                createSource("MUMBAI", "refinery", "HIGH", "COMPLIANT", Arrays.asList("PM2.5", "NO2", "SO2")),
                createSource("BENGALURU", "waste-burning", "MEDIUM", "WARNING", Arrays.asList("PM2.5", "PM10")),
                createSource("DELHI", "power-unit", "HIGH", "COMPLIANT", Arrays.asList("PM2.5", "NO2", "SO2", "CO"))
            );
            repository.saveAll(sources);
        }
    }

    private IndustrialSource createSource(String wardId, String category, String risk, String status, List<String> emissions) {
        return IndustrialSource.builder()
                .facilityId("IND-" + wardId + "-" + category.toUpperCase().replace("-", "_"))
                .facilityName("Demo Facility " + category)
                .category(category)
                .wardId(wardId)
                .location(new GeoJsonPoint(77.20, 28.60))
                .emissionTypes(emissions)
                .riskLevel(risk)
                .complianceStatus(status)
                .build();
    }
}
