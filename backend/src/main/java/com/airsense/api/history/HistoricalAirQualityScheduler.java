package com.airsense.api.history;

import com.airsense.api.entities.TrackedAirQualityLocation;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import com.airsense.api.repositories.TrackedAirQualityLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class HistoricalAirQualityScheduler {
    private final HistoricalAirQualityIngestionService ingestionService;
    private final CpcbAllStationCollectionService cpcbAllStationCollectionService;
    private final TrackedAirQualityLocationRepository trackedLocationRepository;
    private final AqiHistoricalSnapshotRepository snapshotRepository;
    private final HistoricalAqiProperties properties;

    @Scheduled(fixedDelayString = "#{${historical.ingestion-interval-minutes:${HISTORICAL_INGESTION_INTERVAL_MINUTES:60}} * 60000}")
    public void ingestTrackedLocations() {
        if (!properties.isIngestionEnabled()) {
            return;
        }
        try {
            cpcbAllStationCollectionService.collectAllStations();
        } catch (Exception e) {
            log.warn("CPCB all-station hourly ingestion failed reason={}", e.getMessage());
        }
        var locations = trackedLocationRepository.findByTrackingEnabledTrueOrderByLastSearchedAtDesc().stream()
                .limit(properties.getLocationRefreshLimit())
                .toList();
        for (TrackedAirQualityLocation location : locations) {
            try {
                ingestionService.refresh(new HistoricalAirQualityIngestionService.LocationRequest(
                        location.getDisplayName(), location.getCity(), location.getState(), location.getCountry(),
                        location.getLatitude(), location.getLongitude()));
                log.info("Historical AQI ingestion success locationKey={}", location.getLocationKey());
            } catch (Exception e) {
                log.warn("Historical AQI ingestion failed locationKey={} reason={}", location.getLocationKey(), e.getMessage());
            }
            sleepGap();
        }
        cleanupRetention();
    }

    private void cleanupRetention() {
        if (properties.getRetentionDays() > 0) {
            snapshotRepository.deleteByDataOriginNotAndProviderObservedAtBefore(
                    DataOrigin.HISTORICAL_TRAINING_ARCHIVE.name(),
                    Instant.now().minusSeconds(properties.getRetentionDays() * 86_400L));
        }
    }

    private void sleepGap() {
        try {
            Thread.sleep(Math.max(0, properties.getMinRequestGapMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
