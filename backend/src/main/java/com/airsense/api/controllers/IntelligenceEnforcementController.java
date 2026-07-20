package com.airsense.api.controllers;

import com.airsense.api.enforcement.EnforcementIntelligenceService;
import com.airsense.api.enforcement.EnforcementRequest;
import com.airsense.api.enforcement.EnforcementResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/intelligence")
public class IntelligenceEnforcementController {
    @Qualifier("phase24EnforcementIntelligenceService")
    private final EnforcementIntelligenceService enforcementIntelligenceService;

    @GetMapping("/enforcement")
    public ResponseEntity<EnforcementResult> getEnforcement(
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String wardId,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        return ResponseEntity.ok(enforcementIntelligenceService.recommend(EnforcementRequest.builder()
                .cityId(cityId)
                .wardId(wardId)
                .latitude(latitude)
                .longitude(longitude)
                .build()));
    }
}
