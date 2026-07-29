package com.airsense.api.controllers;

import com.airsense.api.ingestion.ForecastOrchestrator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/forecast")
public class ForecastController {

    private final ObjectProvider<ForecastOrchestrator> forecastOrchestrator;

    public ForecastController(ObjectProvider<ForecastOrchestrator> forecastOrchestrator) {
        this.forecastOrchestrator = forecastOrchestrator;
    }

    @PostMapping("/run")
    public ResponseEntity<?> runForecast() {
        ForecastOrchestrator orchestrator = forecastOrchestrator.getIfAvailable();
        if (orchestrator == null) {
            return ResponseEntity.status(410).body(Map.of(
                    "status", "disabled",
                    "message", "Legacy /predict batch forecasting is disabled. Use /api/v1/intelligence/forecast or decision intelligence."
            ));
        }
        new Thread(orchestrator::runForecasts).start();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Forecast generation pipeline triggered"
        ));
    }
}
