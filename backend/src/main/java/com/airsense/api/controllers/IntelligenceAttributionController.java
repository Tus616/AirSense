package com.airsense.api.controllers;

import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionAttributionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/intelligence")
public class IntelligenceAttributionController {
    private final PollutionAttributionService attributionService;

    @GetMapping("/attribution")
    public ResponseEntity<AttributionResult> getAttribution(
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) String placeId,
            @RequestParam(required = false) String cityName,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String wardId,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        return ResponseEntity.ok(attributionService.attribute(AttributionRequest.builder()
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
