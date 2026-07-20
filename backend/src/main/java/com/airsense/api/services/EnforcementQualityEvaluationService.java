package com.airsense.api.services;

import com.airsense.api.entities.EnforcementRecommendation;
import com.airsense.api.entities.EvaluationMetrics.EnforcementMetrics;
import com.airsense.api.repositories.EnforcementRecommendationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EnforcementQualityEvaluationService {

    @Autowired
    private EnforcementRecommendationRepository repository;

    public EnforcementMetrics evaluate() {
        List<EnforcementRecommendation> actions = repository.findAll();

        int evaluatedActionCount = actions.size();
        if (evaluatedActionCount == 0) {
            return EnforcementMetrics.builder().build();
        }

        double totalScore = 0;
        int passed = 0;
        int failed = 0;
        Map<String, Integer> failureReasons = new HashMap<>();

        for (EnforcementRecommendation action : actions) {
            double score = 100.0;
            boolean failedFlag = false;

            if (action.getTarget() == null || action.getTarget().isEmpty()) {
                score -= 20;
                failureReasons.put("Missing Target", failureReasons.getOrDefault("Missing Target", 0) + 1);
                failedFlag = true;
            }
            if (action.getActionType() == null || action.getActionType().isEmpty()) {
                score -= 20;
                failureReasons.put("Missing Action Type", failureReasons.getOrDefault("Missing Action Type", 0) + 1);
                failedFlag = true;
            }
            if (action.getPriorityScore() < 0 || action.getPriorityScore() > 100) {
                score -= 10;
                failureReasons.put("Invalid Priority Score", failureReasons.getOrDefault("Invalid Priority Score", 0) + 1);
                failedFlag = true;
            }
            if (action.getEvidence() == null || action.getEvidence().isEmpty()) {
                score -= 30;
                failureReasons.put("Missing Evidence", failureReasons.getOrDefault("Missing Evidence", 0) + 1);
                failedFlag = true;
            }
            if (action.getExpectedImpact() == null || action.getExpectedImpact().trim().length() < 10) {
                score -= 10;
                failureReasons.put("Weak Justification", failureReasons.getOrDefault("Weak Justification", 0) + 1);
                failedFlag = true;
            }
            if (action.getStatus() == null) {
                score -= 10;
                failureReasons.put("Missing Status", failureReasons.getOrDefault("Missing Status", 0) + 1);
                failedFlag = true;
            }

            if (score < 0) score = 0;
            totalScore += score;

            if (failedFlag || score < 80) {
                failed++;
            } else {
                passed++;
            }
        }

        double averageScore = totalScore / evaluatedActionCount;

        return EnforcementMetrics.builder()
                .averageQualityScore(averageScore)
                .evaluatedActionCount(evaluatedActionCount)
                .passedActionCount(passed)
                .failedActionCount(failed)
                .commonFailureReasons(failureReasons)
                .build();
    }
}
