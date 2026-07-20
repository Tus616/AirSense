package com.airsense.api.services;

import com.airsense.api.entities.CitizenRiskProfile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CitizenRiskScoringService {

    public String calculateRiskCategory(int baseAqi, CitizenRiskProfile profile) {
        if (profile == null || profile.getVulnerabilities() == null || profile.getVulnerabilities().isEmpty()) {
            return mapAqiToCategory(baseAqi);
        }

        double multiplier = 1.0;
        List<String> vulns = profile.getVulnerabilities();
        
        if (vulns.contains("ASTHMA")) multiplier += 0.3;
        if (vulns.contains("COPD")) multiplier += 0.3;
        if (vulns.contains("ELDERLY")) multiplier += 0.2;
        if (vulns.contains("CHILD")) multiplier += 0.2;
        if (vulns.contains("PREGNANCY")) multiplier += 0.2;
        if (vulns.contains("OUTDOOR_WORKER")) multiplier += 0.15;

        int effectiveAqi = (int) (baseAqi * multiplier);
        return mapAqiToCategory(effectiveAqi);
    }

    private String mapAqiToCategory(int aqi) {
        if (aqi <= 50) return "LOW";
        if (aqi <= 100) return "MODERATE";
        if (aqi <= 200) return "HIGH";
        return "SEVERE";
    }
}
