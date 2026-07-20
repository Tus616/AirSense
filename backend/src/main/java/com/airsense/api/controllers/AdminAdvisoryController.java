package com.airsense.api.controllers;

import com.airsense.api.entities.Prediction;
import com.airsense.api.repositories.PredictionRepository;
import com.airsense.api.services.AdvisoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/admin/advisories")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAdvisoryController {

    @Autowired
    private AdvisoryService advisoryService;

    @Autowired
    private PredictionRepository predictionRepository;

    @PostMapping("/run")
    public ResponseEntity<?> runAdvisoryGeneration(@RequestParam(required = false) String wardId, 
                                                   @RequestParam(required = false, defaultValue = "false") boolean all) {
        if (all) {
            new Thread(() -> {
                List<Prediction> latestPredictions = predictionRepository.findAll();
                // In a real app we'd get the distinct latest prediction per ward
                for (Prediction p : latestPredictions) {
                    advisoryService.generateAdvisoryForPrediction(p);
                }
            }).start();
            return ResponseEntity.ok(Map.of("message", "Triggered advisory generation for all wards"));
        } else if (wardId != null) {
            Optional<Prediction> predictionOpt = predictionRepository.findTopByWardIdOrderByGeneratedAtDesc(wardId);
            if (predictionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No prediction found for ward " + wardId));
            }
            new Thread(() -> {
                advisoryService.generateAdvisoryForPrediction(predictionOpt.get());
            }).start();
            return ResponseEntity.ok(Map.of("message", "Triggered advisory generation for ward " + wardId));
        }
        
        return ResponseEntity.badRequest().body(Map.of("error", "Must provide wardId or all=true"));
    }
}
