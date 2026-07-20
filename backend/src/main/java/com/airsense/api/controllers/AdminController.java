package com.airsense.api.controllers;

import com.airsense.api.services.AttributionEngineService;
import com.airsense.api.services.GridGenerationService;
import com.airsense.api.ingestion.ForecastOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @Autowired
    private AttributionEngineService engineService;

    @Autowired
    private GridGenerationService gridGenerationService;

    @Autowired
    private ForecastOrchestrator forecastOrchestrator;

    @PostMapping("/attribution/run")
    public ResponseEntity<?> runAttribution(
            @RequestParam String wardId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        
        engineService.runAttribution(wardId, from, to);
        return ResponseEntity.ok(Map.of("message", "Attribution engine run completed for ward: " + wardId));
    }

    @PostMapping("/grid/generate")
    public ResponseEntity<?> generateGrid(@RequestParam String cityId) {
        var cells = gridGenerationService.generateGrid(cityId);
        return ResponseEntity.ok(Map.of(
            "message", "Grid generated successfully",
            "cellsGenerated", cells.size()
        ));
    }

    @PostMapping("/forecast/grid/run")
    public ResponseEntity<?> runGridForecast(@RequestParam String cityId) {
        forecastOrchestrator.runForecasts();
        return ResponseEntity.ok(Map.of("message", "Forecast orchestrator triggered"));
    }
}
