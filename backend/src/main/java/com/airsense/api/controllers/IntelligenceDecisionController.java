package com.airsense.api.controllers;

import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.DecisionIntelligenceService;
import com.airsense.api.decision.DecisionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/intelligence")
public class IntelligenceDecisionController {
    private final DecisionIntelligenceService decisionIntelligenceService;

    @GetMapping("/decision")
    public ResponseEntity<DecisionIntelligenceResult> getDecision(
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String placeId,
            @RequestParam(required = false) String cityName,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String wardId,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false, defaultValue = "false") boolean refreshEvidence) {
        return ResponseEntity.ok(decisionIntelligenceService.decide(DecisionRequest.builder()
                .cityId(cityId)
                .placeId(placeId)
                .cityName(cityName)
                .state(state)
                .country(country)
                .wardId(wardId)
                .latitude(latitude)
                .longitude(longitude)
                .refresh(refreshEvidence)
                .build()));
    }
}
