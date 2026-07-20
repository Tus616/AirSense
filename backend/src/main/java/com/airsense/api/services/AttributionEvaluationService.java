package com.airsense.api.services;

import com.airsense.api.entities.AttributionResult;
import com.airsense.api.entities.EvaluationMetrics.AttributionMetrics;
import com.airsense.api.entities.SyntheticPollutionEvent;
import com.airsense.api.repositories.AttributionResultRepository;
import com.airsense.api.repositories.SyntheticPollutionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class AttributionEvaluationService {

    @Autowired
    private SyntheticPollutionEventRepository syntheticRepository;

    @Autowired
    private AttributionResultRepository attributionRepository;

    public AttributionMetrics evaluate() {
        List<SyntheticPollutionEvent> events = syntheticRepository.findAll();
        
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        Map<String, Integer> confusion = new HashMap<>();

        for (SyntheticPollutionEvent event : events) {
            // Find the corresponding attribution result
            // Usually we'd search by time window, but for this evaluation harness, 
            // we look for the most recent attribution result for the same ward within a short window.
            // Or since this is a prototype, just grab the latest one for the ward.
            Optional<AttributionResult> resultOpt = attributionRepository.findTopByWardIdOrderByTimestampDesc(event.getWardId());
            
            if (resultOpt.isPresent()) {
                AttributionResult result = resultOpt.get();
                if (result.getRankedSources() != null && !result.getRankedSources().isEmpty()) {
                    String predictedSource = result.getRankedSources().get(0).getCategory();
                    String trueSource = event.getTrueSource().toLowerCase();
                    predictedSource = predictedSource.toLowerCase();

                    if (predictedSource.equals(trueSource)) {
                        truePositives++;
                    } else {
                        falsePositives++;
                        falseNegatives++; // Simplification for multi-class
                        
                        String key = trueSource + "->" + predictedSource;
                        confusion.put(key, confusion.getOrDefault(key, 0) + 1);
                    }
                } else {
                    falseNegatives++;
                }
            } else {
                falseNegatives++;
            }
        }

        int evaluatedCount = events.size();
        double precision = (truePositives + falsePositives) == 0 ? 0 : (double) truePositives / (truePositives + falsePositives);
        double recall = (truePositives + falseNegatives) == 0 ? 0 : (double) truePositives / (truePositives + falseNegatives);
        double f1 = (precision + recall) == 0 ? 0 : 2 * (precision * recall) / (precision + recall);
        double accuracy = evaluatedCount == 0 ? 0 : (double) truePositives / evaluatedCount;

        return AttributionMetrics.builder()
                .precision(precision)
                .recall(recall)
                .f1(f1)
                .accuracy(accuracy)
                .evaluatedCount(evaluatedCount)
                .confusion(confusion)
                .build();
    }
}
