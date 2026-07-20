package com.airsense.api.controllers;

import com.airsense.api.entities.EvaluationMetrics;
import com.airsense.api.services.EvaluationHarnessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationHarnessService harnessService;

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EvaluationMetrics> runEvaluation() {
        return ResponseEntity.ok(harnessService.runEvaluation());
    }

    @GetMapping("/latest")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EvaluationMetrics> getLatestEvaluation() {
        EvaluationMetrics latest = harnessService.getLatestEvaluation();
        if (latest == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(latest);
    }
}
