package com.airsense.api.services;

import com.airsense.api.entities.EnforcementRecommendation;
import com.airsense.api.entities.EvaluationMetrics.ResponseTimeMetrics;
import com.airsense.api.entities.Prediction;
import com.airsense.api.repositories.EnforcementRecommendationRepository;
import com.airsense.api.repositories.PredictionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ResponseTimeEvaluationService {

    @Autowired
    private EnforcementRecommendationRepository enforcementRepository;

    @Autowired
    private PredictionRepository predictionRepository;

    public ResponseTimeMetrics evaluate() {
        List<EnforcementRecommendation> actions = enforcementRepository.findAll();

        int evaluatedCount = 0;
        long totalMs = 0;
        long worstCaseMs = 0;
        long bestCaseMs = Long.MAX_VALUE;

        for (EnforcementRecommendation action : actions) {
            Instant actionAt = action.getGeneratedAt();
            Instant forecastAt = null;

            if (action.getForecastRef() != null && !action.getForecastRef().isEmpty()) {
                Optional<Prediction> predOpt = predictionRepository.findById(action.getForecastRef());
                if (predOpt.isPresent()) {
                    try {
                        forecastAt = Instant.parse(predOpt.get().getGeneratedAt());
                    } catch (Exception ignored) {}
                }
            }
            
            if (actionAt != null && forecastAt != null) {
                long latencyMs = actionAt.toEpochMilli() - forecastAt.toEpochMilli();
                if (latencyMs > 0) {
                    evaluatedCount++;
                    totalMs += latencyMs;
                    worstCaseMs = Math.max(worstCaseMs, latencyMs);
                    bestCaseMs = Math.min(bestCaseMs, latencyMs);
                }
            }
        }

        long averageMs = evaluatedCount == 0 ? 0 : totalMs / evaluatedCount;
        if (bestCaseMs == Long.MAX_VALUE) bestCaseMs = 0;

        return ResponseTimeMetrics.builder()
                .averageMs(averageMs)
                .worstCaseMs(worstCaseMs)
                .bestCaseMs(bestCaseMs)
                .evaluatedCount(evaluatedCount)
                .build();
    }
}
