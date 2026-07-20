package com.airsense.api.controllers;

import com.airsense.api.dto.*;
import com.airsense.api.services.AqiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/aqi")
public class AqiController {

    @Autowired
    private AqiService aqiService;

    @GetMapping("/city-summary")
    public ResponseEntity<CityAqiSummaryDto> getCitySummary(
            @RequestParam(required = false) String cityId) {
        return ResponseEntity.ok(aqiService.getCitySummary(safeCityId(cityId)));
    }

    @GetMapping("/neighborhoods")
    public ResponseEntity<List<NeighborhoodDto>> getNeighborhoods(
            @RequestParam(required = false) String cityId) {
        return ResponseEntity.ok(aqiService.getNeighborhoods(safeCityId(cityId)));
    }

    @GetMapping("/forecast")
    public ResponseEntity<?> getForecast(
            @RequestParam(required = false) String wardId,
            @RequestParam(required = false) String gridId) {
        
        if (gridId != null && !gridId.isEmpty()) {
            return ResponseEntity.ok(aqiService.getForecastByGrid(gridId));
        } else if (wardId != null && !wardId.isEmpty()) {
            return ResponseEntity.ok(aqiService.getForecastByWard(wardId));
        } else {
            return ResponseEntity.ok(aqiService.getForecast());
        }
    }

    /**
     * Source attribution for a ward — returns feature-importance breakdown
     * mapped to human-readable source names (e.g. "Wind Speed", "Traffic Density").
     */
    @GetMapping("/source-attribution/{wardId}")
    public ResponseEntity<List<SourceAttributionDto>> getSourceAttribution(@PathVariable String wardId) {
        return ResponseEntity.ok(aqiService.getSourceAttribution(wardId));
    }

    @GetMapping("/advisory")
    public ResponseEntity<?> getAdvisory(@RequestParam(required = false) String wardId) {
        if (wardId != null) {
            return aqiService.getAdvisoryByWard(wardId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.ok(aqiService.getAdvisory())); // fallback to city-level advisory
        }
        return ResponseEntity.ok(aqiService.getAdvisory()); // fallback to mock for city-level
    }

    private String safeCityId(String cityId) {
        return cityId == null || cityId.isBlank() ? "UNKNOWN_PLACE" : cityId;
    }
}
