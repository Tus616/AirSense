package com.airsense.api.controllers;

import com.airsense.api.entities.CitizenRiskAdvisory;
import com.airsense.api.repositories.CitizenRiskAdvisoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/public")
public class PublicController {

    @Autowired
    private CitizenRiskAdvisoryRepository advisoryRepository;

    @GetMapping("/ward-display/{wardId}")
    public ResponseEntity<Map<String, Object>> getWardDisplay(
            @PathVariable String wardId,
            @RequestParam(required = false, defaultValue = "UNKNOWN_PLACE") String cityId,
            @RequestParam(required = false, defaultValue = "en") String language) {

        Optional<CitizenRiskAdvisory> latestOpt = advisoryRepository.findTopByWardIdAndLanguageOrderByGeneratedAtDesc(wardId, language);
        
        Map<String, Object> response = new HashMap<>();
        response.put("wardId", wardId);
        response.put("cityId", cityId);
        response.put("language", language);
        
        if (latestOpt.isPresent()) {
            CitizenRiskAdvisory advisory = latestOpt.get();
            response.put("currentAqi", advisory.getCurrentAqi());
            response.put("riskLevel", advisory.getRiskLevel());
            response.put("riskScore", advisory.getWardRiskScore()); // Public display only gets ward score
            response.put("topAdvisoryLine", advisory.getTopLine());
            response.put("forecastPeakAqi", advisory.getForecastPeakAqi());
            response.put("forecastPeakAt", advisory.getForecastPeakAt());
            response.put("updatedAt", advisory.getGeneratedAt());
            response.put("displayColor", getDisplayColor(advisory.getRiskLevel()));
            response.put("source", advisory.getSource());
        } else {
            // Provide a default safe response if no advisory generated yet
            response.put("currentAqi", "--");
            response.put("riskLevel", "UNKNOWN");
            response.put("riskScore", 0);
            response.put("topAdvisoryLine", "Data unavailable");
            response.put("displayColor", "#9CA3AF");
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/ivr/{wardId}")
    public ResponseEntity<Map<String, Object>> getIvrScript(
            @PathVariable String wardId,
            @RequestParam(required = false, defaultValue = "UNKNOWN_PLACE") String cityId,
            @RequestParam(required = false, defaultValue = "en") String language) {

        Optional<CitizenRiskAdvisory> latestOpt = advisoryRepository.findTopByWardIdAndLanguageOrderByGeneratedAtDesc(wardId, language);
        
        Map<String, Object> response = new HashMap<>();
        response.put("wardId", wardId);
        response.put("language", language);
        response.put("prototype", true);
        response.put("ttsProvider", "mock");

        if (latestOpt.isPresent()) {
            response.put("riskLevel", latestOpt.get().getRiskLevel());
            response.put("ivrScript", latestOpt.get().getIvrScript());
        } else {
            response.put("riskLevel", "UNKNOWN");
            response.put("ivrScript", "Information currently unavailable for this area.");
        }

        return ResponseEntity.ok(response);
    }

    private String getDisplayColor(String riskLevel) {
        if (riskLevel == null) return "#9CA3AF"; // Gray
        switch (riskLevel.toUpperCase()) {
            case "LOW": return "#10B981"; // Green
            case "MODERATE": return "#F59E0B"; // Yellow
            case "HIGH": return "#EF4444"; // Red
            case "SEVERE": return "#7F1D1D"; // Dark Red
            default: return "#9CA3AF";
        }
    }
}
