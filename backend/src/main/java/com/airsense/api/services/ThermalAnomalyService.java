package com.airsense.api.services;

import com.airsense.api.entities.ThermalAnomaly;
import com.airsense.api.repositories.ThermalAnomalyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class ThermalAnomalyService {

    @Autowired
    private ThermalAnomalyRepository repository;

    public void seedData() {
        if (repository.count() == 0) {
            Instant now = Instant.now();
            List<ThermalAnomaly> anomalies = Arrays.asList(
                createAnomaly("DELHI", now.minus(2, ChronoUnit.HOURS), 45.2, "HIGH"),
                createAnomaly("DELHI", now.minus(12, ChronoUnit.HOURS), 38.5, "MEDIUM"),
                createAnomaly("MUMBAI", now.minus(5, ChronoUnit.HOURS), 42.1, "HIGH"),
                createAnomaly("BENGALURU", now.minus(1, ChronoUnit.DAYS), 35.0, "LOW")
            );
            repository.saveAll(anomalies);
        }
    }

    private ThermalAnomaly createAnomaly(String wardId, Instant time, double temp, String intensity) {
        return ThermalAnomaly.builder()
                .anomalyId("TA-" + UUID.randomUUID().toString().substring(0, 8))
                .wardId(wardId)
                .timestamp(time)
                .location(new GeoJsonPoint(77.20, 28.60))
                .surfaceTemperature(temp)
                .thresholdTemperature(35.0)
                .intensity(intensity)
                .sourceSatellite("fixture")
                .build();
    }

    public List<ThermalAnomaly> getAnomaliesForWard(String wardId) {
        return repository.findByWardId(wardId);
    }
}
