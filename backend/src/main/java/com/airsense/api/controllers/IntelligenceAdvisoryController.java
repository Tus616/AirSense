package com.airsense.api.controllers;

import com.airsense.api.advisory.HealthAdvisoryRequest;
import com.airsense.api.advisory.HealthAdvisoryResult;
import com.airsense.api.advisory.HealthAdvisoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/intelligence")
public class IntelligenceAdvisoryController {
    private final HealthAdvisoryService healthAdvisoryService;

    @GetMapping("/advisory")
    public ResponseEntity<HealthAdvisoryResult> getAdvisory(
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String wardId,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        return ResponseEntity.ok(healthAdvisoryService.generate(HealthAdvisoryRequest.builder()
                .cityId(cityId)
                .wardId(wardId)
                .latitude(latitude)
                .longitude(longitude)
                .build()));
    }
}
