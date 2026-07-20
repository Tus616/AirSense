package com.airsense.api.controllers;

import com.airsense.api.ingestion.ForecastOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/forecast")
public class ForecastController {

    @Autowired
    private ForecastOrchestrator forecastOrchestrator;

    @PostMapping("/run")
    public ResponseEntity<?> runForecast() {
        new Thread(() -> forecastOrchestrator.runForecasts()).start();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Forecast generation pipeline triggered"
        ));
    }
}
