package com.airsense.api.forecast;

import com.airsense.api.entities.AqiForecastRun;
import com.airsense.api.entities.ForecastRetrainingRequest;
import com.airsense.api.repositories.AqiForecastRunRepository;
import com.airsense.api.repositories.ForecastRetrainingRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Drift monitoring service that periodically checks forecast accuracy
 * against evaluated actuals and raises warnings when performance degrades.
 *
 * Drift detection:
 * - Compares recent-window RMSE/MAE against the training baseline
 * - If RMSE exceeds the configured threshold, logs a DRIFT_DETECTED warning
 * - Optionally triggers retraining via the AI service /train endpoint
 *
 * This service does NOT modify any existing code or data. It is purely additive.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastDriftMonitorService {
    private final AqiForecastRunRepository forecastRunRepository;
    private final ForecastRetrainingRequestRepository retrainingRequestRepository;
    private final ForecastEngineProperties engineProperties;
    private final MlForecastProperties mlProperties;

    // Drift detection parameters
    private static final int DRIFT_WINDOW_DAYS = 7;
    private static final int MIN_EVALUATED_RUNS = 10;
    private static final double RMSE_DRIFT_THRESHOLD = 50.0;
    private static final double MAE_DRIFT_THRESHOLD = 40.0;
    private static final double BIAS_DRIFT_THRESHOLD = 25.0;

    private volatile DriftStatus lastStatus = null;

    /**
     * Scheduled drift check — runs every 6 hours.
     * Only enabled when forecast evaluation is enabled.
     */
    @Scheduled(fixedDelayString = "#{${forecast.engine.evaluation-enabled:${FORECAST_EVALUATION_ENABLED:true}} ? 21600000 : 2147483647}")
    public void checkDrift() {
        if (!engineProperties.isEvaluationEnabled()) return;
        try {
            DriftStatus status = computeDriftStatus();
            lastStatus = status;

            if (status.driftDetected) {
                log.warn("DRIFT_DETECTED window={}d evaluatedRuns={} rmse={} mae={} bias={} thresholdRmse={} thresholdMae={}",
                        DRIFT_WINDOW_DAYS, status.evaluatedCount, status.rmse, status.mae, status.bias,
                        RMSE_DRIFT_THRESHOLD, MAE_DRIFT_THRESHOLD);
            } else {
                log.info("DRIFT_CHECK_OK window={}d evaluatedRuns={} rmse={} mae={} bias={}",
                        DRIFT_WINDOW_DAYS, status.evaluatedCount, status.rmse, status.mae, status.bias);
            }
        } catch (Exception e) {
            log.warn("Drift check failed: {}", e.getMessage());
        }
    }

    /**
     * Returns the current drift status as a structured map.
     * Used by the debug/monitoring endpoints.
     */
    public Map<String, Object> status() {
        DriftStatus status = lastStatus;
        if (status == null) {
            // Compute on demand if no scheduled check has run
            status = computeDriftStatus();
            lastStatus = status;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("driftDetected", status.driftDetected);
        result.put("windowDays", DRIFT_WINDOW_DAYS);
        result.put("evaluatedCount", status.evaluatedCount);
        result.put("evaluatedForecastCount", status.evaluatedCount);
        result.put("skippedImmatureCount", status.skippedImmatureCount);
        result.put("skippedMissingActualCount", status.skippedMissingActualCount);
        result.put("stationMismatchCount", status.stationMismatchCount);
        result.put("standardMismatchCount", status.standardMismatchCount);
        result.put("matchingToleranceMinutes", status.matchingToleranceMinutes);
        result.put("rmse", status.rmse);
        result.put("mae", status.mae);
        result.put("bias", status.bias);
        result.put("modelMetrics", status.modelMetrics);
        result.put("baselineMetrics", status.baselineMetrics);
        result.put("errorSamples", status.errorSamples);
        result.put("rmseThreshold", RMSE_DRIFT_THRESHOLD);
        result.put("maeThreshold", MAE_DRIFT_THRESHOLD);
        result.put("biasThreshold", BIAS_DRIFT_THRESHOLD);
        result.put("checkedAt", status.checkedAt.toString());
        result.put("driftReasons", status.driftReasons);
        result.put("driftDecisionReason", status.driftDecisionReason);
        result.put("recommendation", status.driftDetected
                ? "Create a retraining request for external pipeline processing; no in-request training is triggered."
                : "No drift detected. Model performance is within acceptable bounds.");
        return result;
    }

    /**
     * Records a retraining request for an external CI/CD pipeline.
     * This does not train inside the Spring request path.
     */
    public Map<String, Object> triggerRetrain(String stationKey, String modelScope, Integer horizonHours, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!mlProperties.isEnabled()) {
            result.put("status", "SKIPPED");
            result.put("reason", "ML forecast service is not enabled");
            return result;
        }
        String safeStationKey = valueOrDefault(stationKey, "GLOBAL");
        String safeScope = valueOrDefault(modelScope, "STATION").toUpperCase();
        Integer safeHorizon = horizonHours != null && horizonHours > 0 ? horizonHours : 24;
        List<ForecastRetrainingRequest> active = retrainingRequestRepository
                .findByStationKeyAndModelScopeAndHorizonHoursAndStatusIn(
                        safeStationKey, safeScope, safeHorizon, List.of("PENDING_EXTERNAL_PIPELINE", "IN_PROGRESS"));
        if (!active.isEmpty()) {
            ForecastRetrainingRequest existing = active.get(0);
            result.put("status", "DEDUPED");
            result.put("requestId", existing.getId());
            result.put("request", existing);
            return result;
        }
        DriftStatus status = computeDriftStatus();
        ForecastRetrainingRequest request = retrainingRequestRepository.save(ForecastRetrainingRequest.builder()
                .stationKey(safeStationKey)
                .modelScope(safeScope)
                .horizonHours(safeHorizon)
                .reason(valueOrDefault(reason, status.driftDecisionReason))
                .metrics(Map.of(
                        "rmse", status.rmse,
                        "mae", status.mae,
                        "bias", status.bias,
                        "modelMetrics", status.modelMetrics,
                        "baselineMetrics", status.baselineMetrics
                ))
                .sampleSize(status.evaluatedCount)
                .requestedAt(Instant.now())
                .status("PENDING_EXTERNAL_PIPELINE")
                .build());
        result.put("status", request.getStatus());
        result.put("requestId", request.getId());
        result.put("request", request);
        return result;
    }

    public Map<String, Object> retrainingRequests() {
        return Map.of("requests", retrainingRequestRepository.findAll());
    }

    private DriftStatus computeDriftStatus() {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(DRIFT_WINDOW_DAYS));
        List<AqiForecastRun> allRuns = forecastRunRepository.findByGeneratedAtBetween(start, end);
        List<AqiForecastRun> evaluatedRuns = forecastRunRepository.findByEvaluatedTrueAndGeneratedAtBetween(start, end);
        List<AqiForecastRun> validRuns = evaluatedRuns.stream()
                .filter(run -> run.getActualAqi() != null && run.getPredictedAqi() != null)
                .filter(run -> run.getGeneratedAt() != null && run.getTargetTime() != null && run.getGeneratedAt().isBefore(run.getTargetTime()))
                .toList();
        int tolerance = Math.max(1, engineProperties.getEvaluationToleranceMinutes());
        long skippedImmature = allRuns.stream()
                .filter(run -> run.getTargetTime() == null || run.getTargetTime().isAfter(end))
                .count();
        long skippedMissingActual = allRuns.stream()
                .filter(run -> !Boolean.TRUE.equals(run.getEvaluated()))
                .filter(run -> run.getTargetTime() != null && !run.getTargetTime().isAfter(end))
                .count();

        if (validRuns.size() < MIN_EVALUATED_RUNS) {
            return new DriftStatus(false, validRuns.size(), (int) skippedImmature, (int) skippedMissingActual, 0, 0, tolerance,
                    0.0, 0.0, 0.0, Instant.now(),
                    List.of("Insufficient evaluated runs (" + validRuns.size() + "/" + MIN_EVALUATED_RUNS + ")"),
                    "MIN_SAMPLE_NOT_MET", Map.of(), Map.of(), errorSamples(validRuns));
        }

        double mae = validRuns.stream()
                .map(AqiForecastRun::getAbsoluteError)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);

        double rmse = Math.sqrt(validRuns.stream()
                .map(AqiForecastRun::getSquaredError)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0));

        double bias = validRuns.stream()
                .map(AqiForecastRun::getError)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);

        List<String> driftReasons = new java.util.ArrayList<>();
        boolean driftDetected = false;
        if (rmse > RMSE_DRIFT_THRESHOLD) {
            driftDetected = true;
            driftReasons.add("RMSE " + round(rmse) + " exceeds threshold " + RMSE_DRIFT_THRESHOLD);
        }
        if (mae > MAE_DRIFT_THRESHOLD) {
            driftDetected = true;
            driftReasons.add("MAE " + round(mae) + " exceeds threshold " + MAE_DRIFT_THRESHOLD);
        }
        if (Math.abs(bias) > BIAS_DRIFT_THRESHOLD) {
            driftDetected = true;
            driftReasons.add("Bias " + round(bias) + " exceeds threshold ±" + BIAS_DRIFT_THRESHOLD);
        }

        String decisionReason = driftDetected ? "THRESHOLD_EXCEEDED_WITH_MIN_SAMPLE" : "WITHIN_THRESHOLDS";
        return new DriftStatus(driftDetected, validRuns.size(), (int) skippedImmature, (int) skippedMissingActual, 0, 0, tolerance,
                round(rmse), round(mae), round(bias), Instant.now(), driftReasons, decisionReason,
                metricMap(validRuns.stream().filter(run -> !"PERSISTENCE".equalsIgnoreCase(valueOrDefault(run.getEngine(), ""))).toList()),
                metricMap(validRuns.stream().filter(run -> "PERSISTENCE".equalsIgnoreCase(valueOrDefault(run.getEngine(), ""))).toList()),
                errorSamples(validRuns));
    }

    private Map<String, Object> metricMap(List<AqiForecastRun> runs) {
        if (runs.isEmpty()) {
            return Map.of("evaluatedCount", 0, "status", "NO_SAMPLES");
        }
        double mae = runs.stream().map(AqiForecastRun::getAbsoluteError).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
        double rmse = Math.sqrt(runs.stream().map(AqiForecastRun::getSquaredError).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0));
        double bias = runs.stream().map(AqiForecastRun::getError).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
        return Map.of("evaluatedCount", runs.size(), "mae", round(mae), "rmse", round(rmse), "bias", round(bias), "status", runs.size() >= MIN_EVALUATED_RUNS ? "SUFFICIENT_SAMPLE" : "INSUFFICIENT_SAMPLE");
    }

    private List<Map<String, Object>> errorSamples(List<AqiForecastRun> runs) {
        return runs.stream()
                .limit(10)
                .map(run -> {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("horizonHours", run.getHorizonHours());
                    sample.put("engine", run.getEngine());
                    sample.put("modelVersion", run.getModelVersion());
                    sample.put("forecastStandard", run.getForecastStandard());
                    sample.put("predictedAqi", run.getPredictedAqi());
                    sample.put("actualAqi", run.getActualAqi());
                    sample.put("predictionError", run.getError());
                    sample.put("absoluteError", run.getAbsoluteError());
                    sample.put("generatedBeforeTarget", run.getGeneratedAt() != null && run.getTargetTime() != null && run.getGeneratedAt().isBefore(run.getTargetTime()));
                    sample.put("actualTargetOffsetMinutes", run.getActualObservedAt() != null && run.getTargetTime() != null
                            ? Math.abs(Duration.between(run.getTargetTime(), run.getActualObservedAt()).toMinutes()) : null);
                    return sample;
                })
                .collect(Collectors.toList());
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private record DriftStatus(boolean driftDetected, int evaluatedCount, int skippedImmatureCount,
                               int skippedMissingActualCount, int stationMismatchCount, int standardMismatchCount,
                               int matchingToleranceMinutes, double rmse, double mae, double bias,
                               Instant checkedAt, List<String> driftReasons, String driftDecisionReason,
                               Map<String, Object> modelMetrics, Map<String, Object> baselineMetrics,
                               List<Map<String, Object>> errorSamples) {
    }
}
