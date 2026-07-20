package com.airsense.api.services;

import com.airsense.api.entities.ConstructionPermit;
import com.airsense.api.repositories.ConstructionPermitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.stereotype.Service;
import org.springframework.data.geo.Point;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class ConstructionPermitSeedService {

    @Autowired
    private ConstructionPermitRepository repository;

    public void seedData() {
        if (repository.count() == 0) {
            Instant now = Instant.now();
            List<ConstructionPermit> permits = Arrays.asList(
                createPermit("DELHI", "metro work", now.minus(30, ChronoUnit.DAYS), now.plus(180, ChronoUnit.DAYS), "HIGH", "ACTIVE"),
                createPermit("DELHI", "building construction", now.minus(60, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS), "MEDIUM", "ACTIVE"),
                createPermit("DELHI", "roadwork", now.minus(5, ChronoUnit.DAYS), now.plus(15, ChronoUnit.DAYS), "HIGH", "ACTIVE"),
                createPermit("MUMBAI", "utility digging", now.minus(10, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS), "MEDIUM", "ACTIVE"),
                createPermit("BENGALURU", "demolition", now.minus(2, ChronoUnit.DAYS), now.plus(10, ChronoUnit.DAYS), "HIGH", "ACTIVE")
            );
            repository.saveAll(permits);
        }
    }

    private ConstructionPermit createPermit(String wardId, String type, Instant from, Instant to, String risk, String status) {
        // Mock a simple polygon
        GeoJsonPolygon polygon = new GeoJsonPolygon(Arrays.asList(
                new Point(77.20, 28.60),
                new Point(77.21, 28.60),
                new Point(77.21, 28.61),
                new Point(77.20, 28.61),
                new Point(77.20, 28.60)
        ));

        return ConstructionPermit.builder()
                .permitId("PERMIT-" + UUID.randomUUID().toString().substring(0, 8))
                .wardId(wardId)
                .projectType(type)
                .activeFrom(from)
                .activeTo(to)
                .polygon(polygon)
                .contractor("Mock Contractor Ltd")
                .dustRiskLevel(risk)
                .status(status)
                .build();
    }
}
