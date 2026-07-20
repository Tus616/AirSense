package com.airsense.api.services;

import com.airsense.api.entities.CityMetricsSnapshot;
import com.airsense.api.repositories.CityMetricsSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class CityMetricsAggregatorService {

    @Autowired
    private CityMetricsSnapshotRepository snapshotRepository;

    private static final List<String> CITIES = List.of("DELHI", "MUMBAI", "BENGALURU");

    @Scheduled(cron = "0 30 * * * *")
    public void aggregateMetrics() {
        log.info("Starting Multi-City Metrics Aggregation...");

        for (String cityId : CITIES) {
            try {
                // In a real scenario, this would query SensorData, Prediction, EnforcementRecommendation collections
                // For this implementation, we simulate an incremental update if the city already exists,
                // or just leave it for the seeder.
                
                Optional<CityMetricsSnapshot> latestOpt = snapshotRepository.findFirstByCityIdOrderByTimestampDesc(cityId);
                
                if (latestOpt.isPresent()) {
                    CityMetricsSnapshot latest = latestOpt.get();
                    CityMetricsSnapshot newSnap = new CityMetricsSnapshot();
                    newSnap.setCityId(cityId);
                    newSnap.setTimestamp(Instant.now());
                    
                    newSnap.setCurrentAqi(latest.getCurrentAqi());
                    newSnap.setForecastPeakAqi(latest.getForecastPeakAqi());
                    newSnap.setDominantSource(latest.getDominantSource());
                    newSnap.setSourceMix(latest.getSourceMix());
                    newSnap.setForecastRmse(latest.getForecastRmse());
                    newSnap.setBaselineRmse(latest.getBaselineRmse());
                    newSnap.setOpenEnforcementCount(latest.getOpenEnforcementCount());
                    newSnap.setResolvedEnforcementCount(latest.getResolvedEnforcementCount());
                    newSnap.setAvgResponseTimeHours(latest.getAvgResponseTimeHours());
                    newSnap.setAdvisoryCount(latest.getAdvisoryCount());
                    
                    snapshotRepository.save(newSnap);
                    log.info("Aggregated new snapshot for {}", cityId);
                }
            } catch (Exception e) {
                log.error("Failed to aggregate metrics for city {}: {}", cityId, e.getMessage());
            }
        }
    }
}
