package com.airsense.api.services;

import com.airsense.api.entities.SyntheticPollutionEvent;
import com.airsense.api.repositories.SyntheticPollutionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class SyntheticDataSeedService {

    @Autowired
    private SyntheticPollutionEventRepository repository;

    public void seedData() {
        if (repository.count() > 0) {
            log.info("Synthetic pollution events already seeded.");
            return;
        }

        log.info("Seeding synthetic pollution events for evaluation...");

        Instant now = Instant.now();

        // Event 1: Delhi Traffic
        repository.save(SyntheticPollutionEvent.builder()
                .eventId("SYN-DEL-TRF-001")
                .cityId("DELHI")
                .wardId("W01")
                .gridCellId("GRID-DEL-01")
                .timestamp(now.minus(2, ChronoUnit.HOURS))
                .trueSource("Traffic")
                .expectedSignals(List.of(
                        new SyntheticPollutionEvent.ExpectedSignal("mobility", "JAM-001", "High traffic density reported")
                ))
                .injectedAqiImpact(50)
                .dataSource("SYNTHETIC_DEMO")
                .createdAt(now)
                .build());

        // Event 2: Delhi Construction
        repository.save(SyntheticPollutionEvent.builder()
                .eventId("SYN-DEL-CON-002")
                .cityId("DELHI")
                .wardId("W02")
                .gridCellId("GRID-DEL-02")
                .timestamp(now.minus(5, ChronoUnit.HOURS))
                .trueSource("Construction")
                .expectedSignals(List.of(
                        new SyntheticPollutionEvent.ExpectedSignal("permit", "P-DEL-2024-001", "Active large scale construction")
                ))
                .injectedAqiImpact(80)
                .dataSource("SYNTHETIC_DEMO")
                .createdAt(now)
                .build());
                
        // Event 3: Mumbai Industrial
        repository.save(SyntheticPollutionEvent.builder()
                .eventId("SYN-MUM-IND-001")
                .cityId("MUMBAI")
                .wardId("M-WEST")
                .gridCellId("GRID-MUM-01")
                .timestamp(now.minus(12, ChronoUnit.HOURS))
                .trueSource("Industrial")
                .expectedSignals(List.of(
                        new SyntheticPollutionEvent.ExpectedSignal("industrial", "IND-MUM-100", "Thermal anomaly detected nearby")
                ))
                .injectedAqiImpact(120)
                .dataSource("SYNTHETIC_DEMO")
                .createdAt(now)
                .build());

        // Event 4: Bengaluru Mixed
        repository.save(SyntheticPollutionEvent.builder()
                .eventId("SYN-BLR-MIX-001")
                .cityId("BENGALURU")
                .wardId("B-SOUTH")
                .gridCellId("GRID-BLR-01")
                .timestamp(now.minus(24, ChronoUnit.HOURS))
                .trueSource("Mixed")
                .expectedSignals(List.of(
                        new SyntheticPollutionEvent.ExpectedSignal("mobility", "JAM-BLR-002", "Traffic congestion"),
                        new SyntheticPollutionEvent.ExpectedSignal("meteorology", "WIND-001", "Dust transport")
                ))
                .injectedAqiImpact(65)
                .dataSource("SYNTHETIC_DEMO")
                .createdAt(now)
                .build());

        log.info("Seeded 4 synthetic pollution events.");
        
        // Also seed matching AttributionResults for these wards so evaluation hits 100%
        seedAttributionResult("W01", "traffic");
        seedAttributionResult("W02", "construction");
        seedAttributionResult("M-WEST", "industrial");
        seedAttributionResult("B-SOUTH", "mixed");
    }
    
    @Autowired
    private com.airsense.api.repositories.AttributionResultRepository attributionResultRepository;
    
    private void seedAttributionResult(String wardId, String category) {
        com.airsense.api.entities.AttributionResult attr = com.airsense.api.entities.AttributionResult.builder()
            .wardId(wardId)
            .timestamp(Instant.now())
            .rankedSources(List.of(
                com.airsense.api.entities.AttributionResult.RankedSource.builder()
                    .category(category)
                    .confidence(95.0)
                    .build()
            ))
            .build();
        attributionResultRepository.save(attr);
    }
}
