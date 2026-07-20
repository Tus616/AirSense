package com.airsense.api.ingestion;

import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.GridForecast;
import com.airsense.api.entities.SensorData;
import com.airsense.api.repositories.SensorDataRepository;
import com.airsense.api.repositories.PredictionRepository;
import com.airsense.api.repositories.GridForecastRepository;
import com.airsense.api.repositories.GridCellRepository;
import com.airsense.api.services.AdvisoryService;
import com.airsense.api.services.EnforcementIntelligenceService;
import com.airsense.api.services.ForecastClient;
import com.airsense.api.services.SpatialInterpolationService;
import com.airsense.api.services.DispersionAdjustmentService;
import com.airsense.api.services.SystemMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ForecastOrchestrator {

    @Autowired
    private SensorDataRepository sensorDataRepository;

    @Autowired
    private PredictionRepository predictionRepository;

    @Autowired
    private GridForecastRepository gridForecastRepository;

    @Autowired
    private ForecastClient forecastClient;

    @Autowired
    private EnforcementIntelligenceService enforcementIntelligenceService;

    @Autowired
    private GridCellRepository gridCellRepository;

    @Autowired
    private SpatialInterpolationService spatialInterpolationService;

    @Autowired
    private DispersionAdjustmentService dispersionAdjustmentService;

    private final AdvisoryService advisoryService;
    private final SystemMetricsService metricsService;

    /**
     * Run every hour at minute 15, slightly after data ingestion (which runs at minute 0).
     * Calls the AI service's batch endpoint and upserts predictions per ward.
     */
    @Scheduled(cron = "0 15 * * * *")
    public void generateForecasts() {
        runForecasts();
    }

    public void runForecasts() {
        log.info("Starting scheduled forecast generation...");

        int maxRetries = 3;
        int attempt = 0;
        long backoffMs = 1000;

        while (attempt < maxRetries) {
            try {
                // Get unique sensor IDs from recent data (last 48h)
                Instant cutoff = Instant.now().minus(48, ChronoUnit.HOURS);
                List<SensorData> recentData = sensorDataRepository.findByTimestampBetween(cutoff, Instant.now());

                // Build a map of sensorId → wardId from recent data
                Map<String, String> sensorToWard = recentData.stream()
                        .collect(Collectors.toMap(
                                SensorData::getSensorId,
                                sd -> sd.getWardId() != null ? sd.getWardId() : sd.getSensorId(),
                                (existing, replacement) -> existing  // keep first if duplicates
                        ));

                if (sensorToWard.isEmpty()) {
                    log.warn("No recent sensor data found. Skipping forecast generation.");
                    return;
                }

                log.info("Found {} active sensors for forecast generation", sensorToWard.size());

                // Build batch request payload
                List<Map<String, String>> sensorPairs = sensorToWard.entrySet().stream()
                        .map(entry -> {
                            Map<String, String> pair = new HashMap<>();
                            pair.put("sensorId", entry.getKey());
                            pair.put("wardId", entry.getValue());
                            return pair;
                        })
                        .collect(Collectors.toList());

                // Call the AI service batch endpoint (single HTTP call)
                List<Prediction> predictions = forecastClient.fetchBatchForecasts(sensorPairs);

                // Upsert each prediction: delete existing for ward, then save new one
                int successCount = 0;
                for (Prediction prediction : predictions) {
                    try {
                        predictionRepository.deleteByWardId(prediction.getWardId());
                        predictionRepository.save(prediction);
                        
                        // Trigger Gemini Advisory Generation
                        advisoryService.generateAdvisoryForPrediction(prediction);
                        
                        successCount++;
                        log.debug("Upserted forecast and generated advisory for ward {}", prediction.getWardId());
                    } catch (Exception e) {
                        log.error("Failed to upsert forecast for ward {}: {}",
                                prediction.getWardId(), e.getMessage());
                        throw e; // trigger retry for whole job
                    }
                }

                log.info("Forecast generation complete. Successfully upserted {}/{} predictions.",
                        successCount, sensorPairs.size());

                log.info("Starting grid forecast generation (Phase 10)...");
                var gridCells = gridCellRepository.findByCityId("DELHI");
                if (gridCells != null && !gridCells.isEmpty()) {
                    // IDW Interpolation
                    List<GridForecast> gridForecasts = spatialInterpolationService.interpolateGrid(gridCells, predictions);
                    
                    // Dispersion Adjustment
                    dispersionAdjustmentService.adjustForDispersion(gridForecasts);
                    
                    // Save
                    gridForecastRepository.saveAll(gridForecasts);
                    log.info("Grid Forecast generation complete. Saved {} grid predictions.", gridForecasts.size());
                    metricsService.recordGridForecastRun();
                    
                    // Trigger Enforcement Intelligence Agent
                    enforcementIntelligenceService.generateEnforcementActions(predictions, gridForecasts);
                } else {
                    log.warn("No GridCells found for DELHI. Skipping grid forecasting. Call /api/v1/admin/grid/generate first.");
                }

                metricsService.recordForecastJobSuccess();
                break; // success, exit retry loop
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxRetries) {
                    log.error("Forecast orchestrator run failed after {} attempts: {}", maxRetries, e.getMessage(), e);
                    metricsService.recordForecastJobFailure();
                } else {
                    log.warn("Forecast job attempt {} failed: {}. Retrying in {}ms...", attempt, e.getMessage(), backoffMs);
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    backoffMs *= 2; // exponential backoff
                }
            }
        }
    }
}
