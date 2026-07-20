package com.airsense.api.controllers;

import com.airsense.api.services.SystemMetricsService;
import com.airsense.api.services.SystemMetricsService.SystemMetricsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricsController {

    private final SystemMetricsService metricsService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SystemMetricsDto> getMetrics() {
        return ResponseEntity.ok(metricsService.getMetrics());
    }
}
