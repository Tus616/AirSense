package com.airsense.api.services;

import com.airsense.api.entities.CitizenRiskAdvisory;
import com.airsense.api.entities.EvaluationMetrics.AdvisoryMetrics;
import com.airsense.api.repositories.CitizenRiskAdvisoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AdvisoryEvaluationService {

    @Autowired
    private CitizenRiskAdvisoryRepository repository;

    public AdvisoryMetrics evaluate() {
        List<CitizenRiskAdvisory> advisories = repository.findAll();

        int totalAdvisories = advisories.size();
        if (totalAdvisories == 0) {
            return AdvisoryMetrics.builder().build();
        }

        Set<String> supportedLanguages = Set.of("en", "hi", "kn", "ta");
        Set<String> generatedLanguages = new HashSet<>();
        
        int relevantAdvisoryCount = 0;
        int missingForecastCount = 0;
        int missingRiskCategoryCount = 0;

        for (CitizenRiskAdvisory advisory : advisories) {
            if (advisory.getLanguage() != null) {
                generatedLanguages.add(advisory.getLanguage().toLowerCase());
            }

            boolean isRelevant = true;

            // Check if forecast data is present in text or object
            boolean hasForecast = advisory.getForecastPeakAqi() > 0 || 
                                  (advisory.getAdvisory() != null && advisory.getAdvisory().contains(String.valueOf(advisory.getForecastPeakAqi())));
            if (!hasForecast) {
                missingForecastCount++;
                isRelevant = false;
            }

            // Check if risk level is present
            boolean hasRiskLevel = advisory.getRiskLevel() != null && !advisory.getRiskLevel().isEmpty();
            if (!hasRiskLevel) {
                missingRiskCategoryCount++;
                isRelevant = false;
            }

            if (isRelevant) {
                relevantAdvisoryCount++;
            }
        }

        double relevanceRate = (double) relevantAdvisoryCount / totalAdvisories;
        int languageCoverageCount = 0;
        for (String lang : generatedLanguages) {
            if (supportedLanguages.contains(lang)) {
                languageCoverageCount++;
            }
        }
        
        double languageCoveragePercent = (double) languageCoverageCount / supportedLanguages.size();

        return AdvisoryMetrics.builder()
                .languageCoverageCount(languageCoverageCount)
                .languageCoveragePercent(languageCoveragePercent)
                .relevanceRate(relevanceRate)
                .totalAdvisories(totalAdvisories)
                .relevantAdvisoryCount(relevantAdvisoryCount)
                .missingForecastReferenceCount(missingForecastCount)
                .missingRiskCategoryCount(missingRiskCategoryCount)
                .build();
    }
}
