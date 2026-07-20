package com.airsense.api.services;

import com.airsense.api.entities.User;
import com.airsense.api.entities.VulnerabilityMapping;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class HealthRiskService {

    public Map<String, Object> calculateRisk(int aqi, VulnerabilityMapping mapping, User user) {
        Map<String, Object> result = new HashMap<>();

        // 1. Calculate base AQI risk factor (0-500 scale normalized to roughly 0-60 base points)
        double baseRisk = Math.min((double) aqi / 500.0 * 60.0, 60.0);

        // 2. Ward Vulnerability modifiers (max 20 points)
        double wardRiskScore = baseRisk;
        if (mapping != null) {
            double elderlyMod = mapping.getElderlyPopulationDensity() * 10;
            double childMod = mapping.getChildrenPopulationDensity() * 10;
            double asthmaMod = mapping.getAsthmaPrevalenceEstimate() * 15;
            double outdoorMod = mapping.getOutdoorWorkerZones() != null ? Math.min(mapping.getOutdoorWorkerZones().size() * 2, 5) : 0;
            
            double totalWardMod = Math.min(elderlyMod + childMod + asthmaMod + outdoorMod, 20.0);
            wardRiskScore += totalWardMod;
        }

        // 3. Personal Vulnerability modifiers (max 20 points)
        double personalRiskScore = wardRiskScore;
        if (user != null && Boolean.TRUE.equals(user.getVulnerable())) {
            double personalMod = 5.0; // Base vulnerable boost
            if (user.getConditions() != null) {
                if (user.getConditions().contains("asthma")) personalMod += 10.0;
                if (user.getConditions().contains("COPD")) personalMod += 12.0;
                if (user.getConditions().contains("elderly")) personalMod += 8.0;
                if (user.getConditions().contains("child")) personalMod += 8.0;
                if (user.getConditions().contains("pregnancy")) personalMod += 7.0;
                if (user.getConditions().contains("outdoorWorker")) personalMod += 8.0;
            }
            personalRiskScore += Math.min(personalMod, 20.0);
        }

        wardRiskScore = Math.min(Math.round(wardRiskScore * 10.0) / 10.0, 100.0);
        personalRiskScore = Math.min(Math.round(personalRiskScore * 10.0) / 10.0, 100.0);

        result.put("wardRiskScore", wardRiskScore);
        result.put("personalRiskScore", personalRiskScore);
        result.put("riskLevel", getRiskLevel(personalRiskScore));
        
        return result;
    }

    private String getRiskLevel(double score) {
        if (score < 30) return "LOW";
        if (score < 60) return "MODERATE";
        if (score < 85) return "HIGH";
        return "SEVERE";
    }
}
