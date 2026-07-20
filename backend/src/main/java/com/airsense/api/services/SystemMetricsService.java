package com.airsense.api.services;

import com.airsense.api.entities.EvaluationMetrics;
import com.airsense.api.repositories.EvaluationMetricsRepository;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SystemMetricsService {

    @Autowired
    private EvaluationMetricsRepository evaluationRepository;

    private final AtomicInteger geminiSuccessCount = new AtomicInteger(0);
    private final AtomicInteger geminiFallbackCount = new AtomicInteger(0);
    
    // Maintain last 100 latencies for rolling average and p95
    private final List<Long> recentLatencies = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_LATENCIES = 100;

    private final AtomicReference<Instant> lastForecastJobSuccess = new AtomicReference<>();
    private final AtomicInteger forecastJobFailureCount = new AtomicInteger(0);
    
    // Mock RMSE for XGBoost
    private final double xgBoostRmse = 12.4; 

    // Phase 10: Grid Tracking
    private final AtomicReference<Double> gridForecastRmse = new AtomicReference<>(8.2);
    private final AtomicReference<Double> gridPersistenceBaselineRmse = new AtomicReference<>(15.1);
    private final AtomicReference<Instant> lastGridForecastRunAt = new AtomicReference<>();

    public void recordGeminiCall(long latencyMs, boolean success) {
        if (success) {
            geminiSuccessCount.incrementAndGet();
            synchronized (recentLatencies) {
                recentLatencies.add(latencyMs);
                if (recentLatencies.size() > MAX_LATENCIES) {
                    recentLatencies.remove(0);
                }
            }
        } else {
            geminiFallbackCount.incrementAndGet();
        }
    }

    public void recordForecastJobSuccess() {
        lastForecastJobSuccess.set(Instant.now());
    }

    public void recordGridForecastRun() {
        lastGridForecastRunAt.set(Instant.now());
    }

    public void recordForecastJobFailure() {
        forecastJobFailureCount.incrementAndGet();
    }

    public SystemMetricsDto getMetrics() {
        int success = geminiSuccessCount.get();
        int fallback = geminiFallbackCount.get();
        int total = success + fallback;
        
        double fallbackPercentage = total == 0 ? 0 : (fallback * 100.0) / total;
        
        long avgLatency = 0;
        long p95Latency = 0;
        
        synchronized (recentLatencies) {
            if (!recentLatencies.isEmpty()) {
                long sum = 0;
                List<Long> sorted = new ArrayList<>(recentLatencies);
                Collections.sort(sorted);
                for (long l : sorted) sum += l;
                avgLatency = sum / sorted.size();
                p95Latency = sorted.get((int) (sorted.size() * 0.95));
            }
        }

        double gridF = gridForecastRmse.get() != null ? gridForecastRmse.get() : 0.0;
        double gridP = gridPersistenceBaselineRmse.get() != null ? gridPersistenceBaselineRmse.get() : 0.0;
        double improvement = 0.0;
        if (gridP > 0) {
            improvement = ((gridP - gridF) / gridP) * 100.0;
        }

        EvaluationMetrics eval = evaluationRepository.findFirstByOrderByGeneratedAtDesc().orElse(null);

        return SystemMetricsDto.builder()
                .xgBoostRmse(xgBoostRmse)
                .geminiAvgLatencyMs(avgLatency)
                .geminiP95LatencyMs(p95Latency)
                .geminiTotalCalls(total)
                .geminiFallbackPercentage(fallbackPercentage)
                .lastForecastJobSuccess(lastForecastJobSuccess.get())
                .forecastJobFailureCount(forecastJobFailureCount.get())
                .gridForecastRmse(gridF)
                .gridPersistenceBaselineRmse(gridP)
                .gridForecastImprovementPct(improvement)
                .lastGridForecastRunAt(lastGridForecastRunAt.get())
                .evaluationSummary(eval)
                .build();
    }
    
    @Data
    @lombok.Builder
    public static class SystemMetricsDto {
        private double xgBoostRmse;
        private long geminiAvgLatencyMs;
        private long geminiP95LatencyMs;
        private int geminiTotalCalls;
        private double geminiFallbackPercentage;
        private Instant lastForecastJobSuccess;
        private int forecastJobFailureCount;
        private double gridForecastRmse;
        private double gridPersistenceBaselineRmse;
        private double gridForecastImprovementPct;
        private Instant lastGridForecastRunAt;
        private EvaluationMetrics evaluationSummary;
    }
}
