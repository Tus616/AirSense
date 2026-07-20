package com.airsense.api.services;

import com.airsense.api.entities.EvaluationMetrics;
import com.airsense.api.repositories.EvaluationMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationHarnessService {

    private final AttributionEvaluationService attributionEval;
    private final ForecastEvaluationService forecastEval;
    private final EnforcementQualityEvaluationService enforcementEval;
    private final AdvisoryEvaluationService advisoryEval;
    private final ResponseTimeEvaluationService responseTimeEval;
    
    private final EvaluationMetricsRepository metricsRepository;

    public EvaluationMetrics runEvaluation() {
        log.info("Starting Evaluation Harness run...");
        
        EvaluationMetrics metrics = EvaluationMetrics.builder()
                .generatedAt(Instant.now())
                .attribution(attributionEval.evaluate())
                .forecast(forecastEval.evaluate())
                .enforcement(enforcementEval.evaluate())
                .advisory(advisoryEval.evaluate())
                .responseTime(responseTimeEval.evaluate())
                .dataSource("SYNTHETIC_DEMO")
                .build();
                
        metricsRepository.save(metrics);
        log.info("Evaluation Harness run completed. Saved metrics: {}", metrics.getId());
        
        return metrics;
    }
    
    public EvaluationMetrics getLatestEvaluation() {
        Optional<EvaluationMetrics> latestOpt = metricsRepository.findFirstByOrderByGeneratedAtDesc();
        return latestOpt.orElse(null);
    }
}
