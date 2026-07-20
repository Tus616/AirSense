package com.airsense.api.forecast;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ForecastRetrainingSchedulerTest {

    @Test
    void trainingCommandRunsScheduledRetrainingModuleWithQualityGates() {
        ForecastRetrainingProperties properties = new ForecastRetrainingProperties();
        properties.setPythonExecutable("python");
        properties.setModelDir("models");
        properties.setScopes(List.of("GLOBAL_COLD_START", "GLOBAL_SHORT_HISTORY"));
        properties.setHorizons(List.of(24, 48, 72));
        properties.setMaxLiveGapHours(1.5);
        properties.setMinLiveContiguousHours(73);

        ForecastRetrainingScheduler scheduler = new ForecastRetrainingScheduler(properties, mock(MongoTemplate.class));

        List<String> command = scheduler.buildCommand(false, "weekly");

        assertThat(command).containsExactly(
                "python",
                "-m",
                "forecasting.training.scheduled_retrain",
                "--standard",
                "INDIA_NAQI",
                "--model-dir",
                "models",
                "--scopes",
                "GLOBAL_COLD_START,GLOBAL_SHORT_HISTORY",
                "--horizons",
                "24,48,72",
                "--max-live-gap-hours",
                "1.5",
                "--min-live-contiguous-hours",
                "73",
                "--schedule",
                "weekly",
                "--trigger",
                "weekly",
                "--version-suffix",
                command.get(command.size() - 1)
        );
    }

    @Test
    void qualityCommandDoesNotTrainCandidates() {
        ForecastRetrainingProperties properties = new ForecastRetrainingProperties();
        properties.setPythonExecutable("python");

        ForecastRetrainingScheduler scheduler = new ForecastRetrainingScheduler(properties, mock(MongoTemplate.class));

        List<String> command = scheduler.buildCommand(true, "daily-quality-validation");

        assertThat(command).contains("--quality-only");
        assertThat(command).doesNotContain("--version-suffix");
    }
}
