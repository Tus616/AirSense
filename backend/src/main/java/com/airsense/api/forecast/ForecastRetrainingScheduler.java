package com.airsense.api.forecast;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastRetrainingScheduler {
    private final ForecastRetrainingProperties properties;
    private final MongoTemplate mongoTemplate;

    @Scheduled(cron = "${forecast.retraining.daily-quality-cron:0 15 1 * * *}")
    public void runDailyQualityValidation() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> result = runPythonCycle(true, "daily-quality-validation");
            log.info("Forecast retraining quality validation completed status={} newlyCollectedRowsIncluded={}",
                    result.get("status"), nested(result, "newlyCollectedRowsIncluded"));
        } catch (Exception e) {
            log.warn("Forecast retraining quality validation failed reason={}", e.getMessage());
        }
    }

    @Scheduled(cron = "${forecast.retraining.training-cron:0 30 2 ? * SUN}")
    public void runScheduledTrainingCycle() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> result = runPythonCycle(false, properties.getSchedule());
            log.info("Forecast retraining cycle completed status={} promotionResult={} newlyCollectedRowsIncluded={}",
                    result.get("status"), nested(result, "promotionResult"), nested(result, "newlyCollectedRowsIncluded"));
        } catch (Exception e) {
            log.warn("Forecast retraining cycle failed reason={}", e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = mongoTemplate.findOne(query, Map.class, "forecast_retraining_runs");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("dailyQualityValidationCron", properties.getDailyQualityCron());
        result.put("trainingCron", properties.getTrainingCron());
        result.put("trainingSchedule", properties.getSchedule());
        result.put("scopes", properties.getScopes());
        result.put("horizons", properties.getHorizons());
        result.put("maxLiveGapHours", properties.getMaxLiveGapHours());
        result.put("minLiveContiguousHours", properties.getMinLiveContiguousHours());
        result.put("lastRetrainingRun", latest);
        return result;
    }

    Map<String, Object> runPythonCycle(boolean qualityOnly, String trigger) throws Exception {
        List<String> command = buildCommand(qualityOnly, trigger);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Path.of(properties.getAiServiceDirectory()).toFile());
        builder.redirectErrorStream(true);
        Instant started = Instant.now();
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(output);
            } catch (Exception ignored) {
            }
        }, "forecast-retraining-output-reader");
        reader.setDaemon(true);
        reader.start();
        boolean completed = process.waitFor(Math.max(1, properties.getTimeoutMinutes()), TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Retraining process timed out after " + properties.getTimeoutMinutes() + " minutes");
        }
        reader.join(Duration.ofSeconds(5).toMillis());
        String stdout = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Retraining process exited " + process.exitValue() + ": " + tail(stdout));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PROCESS_COMPLETED");
        result.put("exitCode", process.exitValue());
        result.put("startedAt", started);
        result.put("completedAt", Instant.now());
        result.put("command", command);
        result.put("outputTail", tail(stdout));
        return result;
    }

    List<String> buildCommand(boolean qualityOnly, String trigger) {
        List<String> command = new ArrayList<>();
        command.add(properties.getPythonExecutable());
        command.add("-m");
        command.add("forecasting.training.scheduled_retrain");
        command.add("--standard");
        command.add(properties.getAqiStandard());
        command.add("--model-dir");
        command.add(properties.getModelDir());
        command.add("--scopes");
        command.add(String.join(",", properties.getScopes()));
        command.add("--horizons");
        command.add(joinInts(properties.getHorizons()));
        command.add("--max-live-gap-hours");
        command.add(Double.toString(properties.getMaxLiveGapHours()));
        command.add("--min-live-contiguous-hours");
        command.add(Integer.toString(properties.getMinLiveContiguousHours()));
        command.add("--schedule");
        command.add(properties.getSchedule());
        command.add("--trigger");
        command.add(trigger);
        if (!qualityOnly) {
            command.add("--version-suffix");
            command.add(DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.now()));
        }
        if (qualityOnly) {
            command.add("--quality-only");
        }
        return command;
    }

    private String joinInts(List<Integer> values) {
        List<String> parts = new ArrayList<>();
        for (Integer value : values) {
            parts.add(String.valueOf(value));
        }
        return String.join(",", parts);
    }

    private Object nested(Map<String, Object> result, String key) {
        Object direct = result.get(key);
        if (direct != null) {
            return direct;
        }
        Object lastRun = result.get("lastRetrainingRun");
        if (lastRun instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private String tail(String value) {
        if (value == null) {
            return "";
        }
        int max = 4000;
        return value.length() <= max ? value : value.substring(value.length() - max);
    }
}
