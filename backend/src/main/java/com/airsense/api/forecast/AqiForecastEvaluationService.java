package com.airsense.api.forecast;

import com.airsense.api.entities.AqiForecastRun;
import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.history.CanonicalLocationIdentity;
import com.airsense.api.history.CanonicalLocationIdentityService;
import com.airsense.api.repositories.AqiForecastRunRepository;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AqiForecastEvaluationService {
    private final AqiForecastRunRepository forecastRunRepository;
    private final AqiHistoricalSnapshotRepository historicalSnapshotRepository;
    private final ForecastEngineProperties properties;
    private final CanonicalLocationIdentityService locationIdentityService;

    @Scheduled(fixedDelayString = "#{${forecast.engine.evaluation-enabled:${FORECAST_EVALUATION_ENABLED:true}} ? 3600000 : 2147483647}")
    public void evaluateDueRuns() {
        if (!properties.isEvaluationEnabled()) return;
        Instant now = Instant.now();
        List<AqiForecastRun> dueRuns = forecastRunRepository.findByEvaluatedFalseAndTargetTimeBefore(now);
        for (AqiForecastRun run : dueRuns) {
            try {
                evaluate(run);
            } catch (Exception e) {
                log.warn("Forecast evaluation failed runId={} locationKey={} horizon={}: {}",
                        run.getForecastRunId(), run.getLocationKey(), run.getHorizonHours(), e.getMessage());
            }
        }
    }

    public Map<String, Object> metrics(String city, String state, String country, Double lat, Double lon, int days) {
        CanonicalLocationIdentity identity = locationIdentityService.identity(city, state, country, lat, lon);
        List<String> queryKeys = locationIdentityService.queryKeys(identity);
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(Math.max(1, days)));
        List<AqiForecastRun> runs = forecastRunRepository.findByLocationKeyInAndEvaluatedTrueAndGeneratedAtBetween(queryKeys, start, end);
        Map<String, List<AqiForecastRun>> groups = runs.stream()
                .filter(run -> run.getActualAqi() != null && run.getPredictedAqi() != null)
                .collect(Collectors.groupingBy(this::metricKey, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> metrics = new ArrayList<>();
        groups.forEach((key, group) -> metrics.add(metric(group)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("location", Map.of(
                "locationKey", identity.locationKey(),
                "locationKeyVersion", identity.keyVersion(),
                "legacyLocationKeys", identity.legacyLocationKeys(),
                "city", valueOrDefault(city, "UNKNOWN_PLACE"),
                "state", valueOrDefault(state, ""),
                "country", valueOrDefault(country, "India"),
                "latitude", lat,
                "longitude", lon
        ));
        response.put("sampleWindowDays", Math.max(1, days));
        response.put("evaluatedForecastCount", runs.size());
        response.put("metrics", metrics);
        response.put("warnings", runs.isEmpty() ? List.of("NO_EVALUATED_FORECAST_RUNS") : List.of());
        return response;
    }

    private void evaluate(AqiForecastRun run) {
        String evaluationLocationKey = valueOrDefault(run.getStationLocationKey(), run.getLocationKey());
        if (run.getTargetTime() == null || evaluationLocationKey == null || run.getForecastStandard() == null
                || run.getGeneratedAt() == null || !run.getGeneratedAt().isBefore(run.getTargetTime())) {
            return;
        }
        int tolerance = Math.max(1, properties.getEvaluationToleranceMinutes());
        Instant start = run.getTargetTime().minus(Duration.ofMinutes(tolerance));
        Instant end = run.getTargetTime().plus(Duration.ofMinutes(tolerance));
        historicalSnapshotRepository.findByLocationKeyAndAqiStandardAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
                        evaluationLocationKey, run.getForecastStandard(), start, end)
                .stream()
                .filter(this::validActual)
                .min(Comparator.comparing(snapshot -> Math.abs(Duration.between(run.getTargetTime(), snapshot.getProviderObservedAt()).toMillis())))
                .ifPresent(actual -> saveEvaluation(run, actual));
    }

    private void saveEvaluation(AqiForecastRun run, AqiHistoricalSnapshot actual) {
        double error = run.getPredictedAqi() - actual.getCurrentAqi();
        run.setActualAqi(actual.getCurrentAqi());
        run.setActualObservedAt(actual.getProviderObservedAt());
        run.setError(round(error));
        run.setAbsoluteError(round(Math.abs(error)));
        run.setSquaredError(round(error * error));
        run.setEvaluated(true);
        forecastRunRepository.save(run);
    }

    private Map<String, Object> metric(List<AqiForecastRun> group) {
        AqiForecastRun first = group.get(0);
        int minimumSamples = Math.max(1, properties.getMetricsMinSamples());
        String status = group.isEmpty() ? "NO_SAMPLES" : group.size() < minimumSamples ? "INSUFFICIENT_SAMPLE" : "SUFFICIENT_SAMPLE";
        double mae = group.stream().map(AqiForecastRun::getAbsoluteError).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
        double rmse = Math.sqrt(group.stream().map(AqiForecastRun::getSquaredError).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0));
        double bias = group.stream().map(AqiForecastRun::getError).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
        double baselineMae = group.stream()
                .filter(run -> run.getBaselinePredictedAqi() != null && run.getActualAqi() != null)
                .mapToDouble(run -> Math.abs(run.getBaselinePredictedAqi() - run.getActualAqi()))
                .average()
                .orElse(0.0);
        double baselineRmse = Math.sqrt(group.stream()
                .filter(run -> run.getBaselinePredictedAqi() != null && run.getActualAqi() != null)
                .mapToDouble(run -> Math.pow(run.getBaselinePredictedAqi() - run.getActualAqi(), 2))
                .average()
                .orElse(0.0));
        long intervalHits = group.stream()
                .filter(run -> run.getActualAqi() != null && run.getLowerBound() != null && run.getUpperBound() != null)
                .filter(run -> run.getActualAqi() >= run.getLowerBound() && run.getActualAqi() <= run.getUpperBound())
                .count();

        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("horizonHours", first.getHorizonHours());
        metric.put("engine", first.getEngine());
        metric.put("modelVersion", first.getModelVersion());
        metric.put("forecastStandard", first.getForecastStandard());
        metric.put("evaluatedCount", group.size());
        metric.put("mae", "SUFFICIENT_SAMPLE".equals(status) ? round(mae) : null);
        metric.put("rmse", "SUFFICIENT_SAMPLE".equals(status) ? round(rmse) : null);
        metric.put("meanBias", "SUFFICIENT_SAMPLE".equals(status) ? round(bias) : null);
        metric.put("baselineMae", "SUFFICIENT_SAMPLE".equals(status) ? round(baselineMae) : null);
        metric.put("baselineRmse", "SUFFICIENT_SAMPLE".equals(status) ? round(baselineRmse) : null);
        metric.put("improvementPercent", "SUFFICIENT_SAMPLE".equals(status) && baselineRmse > 0 ? round((baselineRmse - rmse) / baselineRmse * 100.0) : null);
        metric.put("intervalCoverage", "SUFFICIENT_SAMPLE".equals(status) && !group.isEmpty() ? round(intervalHits / (double) group.size()) : null);
        metric.put("status", status);
        return metric;
    }

    private String metricKey(AqiForecastRun run) {
        return run.getHorizonHours() + "|" + run.getEngine() + "|" + run.getModelVersion() + "|" + run.getForecastStandard();
    }

    private boolean validActual(AqiHistoricalSnapshot snapshot) {
        return snapshot.getCurrentAqi() != null
                && snapshot.getCurrentAqi() >= 0
                && snapshot.getCurrentAqi() <= 500
                && snapshot.getProviderObservedAt() != null
                && snapshot.getAqiStandard() != null
                && !"INVALID".equalsIgnoreCase(snapshot.getDataQualityStatus())
                && !"PROVIDER_UNAVAILABLE".equalsIgnoreCase(snapshot.getDataQualityStatus());
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
