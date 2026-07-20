package com.airsense.api.controllers;

import com.airsense.api.dto.CitySnapshotDto;
import com.airsense.api.entities.City;
import com.airsense.api.entities.CityMetricsSnapshot;
import com.airsense.api.repositories.CityRepository;
import com.airsense.api.repositories.CityMetricsSnapshotRepository;
import com.airsense.api.services.CityComparisonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/gov/cities")
public class CityController {

    @Autowired
    private CityMetricsSnapshotRepository snapshotRepository;

    @Autowired
    private CityComparisonService cityComparisonService;

    @Autowired
    private CityRepository cityRepository;

    @GetMapping
    public ResponseEntity<List<String>> getCities() {
        return ResponseEntity.ok(cityRepository.findAll().stream()
                .map(City::getCityId)
                .filter(cityId -> cityId != null && !cityId.isBlank())
                .sorted()
                .toList());
    }

    @GetMapping("/compare")
    public ResponseEntity<List<CitySnapshotDto>> compareCities() {
        return ResponseEntity.ok(cityComparisonService.getComparedCities());
    }

    @GetMapping("/{cityId}/metrics")
    public ResponseEntity<List<CityMetricsSnapshot>> getCityMetrics(@PathVariable String cityId) {
        return ResponseEntity.ok(snapshotRepository.findByCityIdOrderByTimestampDesc(cityId));
    }
}
