package com.airsense.api.controllers;

import com.airsense.api.entities.EnforcementRecommendation;
import com.airsense.api.repositories.EnforcementRecommendationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/gov/enforcement")
public class EnforcementController {

    @Autowired
    private EnforcementRecommendationRepository repository;

    @GetMapping("/recommendations")
    public ResponseEntity<List<EnforcementRecommendation>> getRecommendations(
            @RequestParam(required = false, defaultValue = "UNKNOWN_PLACE") String cityId,
            @RequestParam(required = false) EnforcementRecommendation.Status status) {
        
        if (status != null) {
            return ResponseEntity.ok(repository.findByCityIdAndStatusOrderByPriorityScoreDesc(cityId, status));
        }
        return ResponseEntity.ok(repository.findByCityIdOrderByPriorityScoreDesc(cityId));
    }

    @GetMapping("/recommendations/{id}")
    public ResponseEntity<EnforcementRecommendation> getRecommendation(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/recommendations/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> payload) {
        
        Optional<EnforcementRecommendation> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        EnforcementRecommendation rec = opt.get();
        try {
            EnforcementRecommendation.Status newStatus = EnforcementRecommendation.Status.valueOf(payload.get("status"));
            rec.setStatus(newStatus);
            repository.save(rec);
            return ResponseEntity.ok(rec);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid status"));
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(@RequestParam(required = false, defaultValue = "UNKNOWN_PLACE") String cityId) {
        List<EnforcementRecommendation> all = repository.findByCityIdOrderByPriorityScoreDesc(cityId);
        
        long openCount = all.stream().filter(r -> r.getStatus() == EnforcementRecommendation.Status.OPEN).count();
        long resolvedCount = all.stream().filter(r -> r.getStatus() == EnforcementRecommendation.Status.RESOLVED).count();
        long highConfidenceCount = all.stream().filter(r -> r.getConfidence() >= 0.8).count();
        
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalRecommendations", all.size());
        metrics.put("openRecommendations", openCount);
        metrics.put("resolvedRecommendations", resolvedCount);
        metrics.put("highConfidenceActions", highConfidenceCount);
        metrics.put("avgResolutionTimeHours", null);
        metrics.put("avgResolutionTimeStatus", "unavailable_without_resolution_audit_log");
        
        return ResponseEntity.ok(metrics);
    }
}
