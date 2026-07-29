package com.airsense.api.controllers;

import com.airsense.api.forecast.ForecastRuntimeDiagnostics;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/diagnostics/forecast")
public class ForecastRuntimeDiagnosticsController {
    private final ForecastRuntimeDiagnostics diagnostics;

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime() {
        return ResponseEntity.ok(diagnostics.snapshot());
    }
}
