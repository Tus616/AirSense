package com.airsense.api.controllers;

import com.airsense.api.temporal.TemporalIntelligenceService;
import com.airsense.api.temporal.TemporalRequest;
import com.airsense.api.temporal.TimelineResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/intelligence")
public class IntelligenceTemporalController {
    private final TemporalIntelligenceService temporalIntelligenceService;

    @GetMapping("/timeline")
    public ResponseEntity<TimelineResult> getTimeline(
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String placeId,
            @RequestParam(required = false) String cityName,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String wardId,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        return ResponseEntity.ok(temporalIntelligenceService.timeline(TemporalRequest.builder()
                .cityId(cityId)
                .placeId(placeId)
                .cityName(cityName)
                .state(state)
                .country(country)
                .wardId(wardId)
                .latitude(latitude)
                .longitude(longitude)
                .build()));
    }
}
