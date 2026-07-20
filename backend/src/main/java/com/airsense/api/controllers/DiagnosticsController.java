package com.airsense.api.controllers;

import com.airsense.api.diagnostics.CpcbAqiDiagnosticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticsController {
    private final CpcbAqiDiagnosticsService cpcbAqiDiagnosticsService;

    @GetMapping("/cpcb-aqi")
    public ResponseEntity<Map<String, Object>> cpcbAqi(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam String city,
            @RequestParam String state) {
        return ResponseEntity.ok(cpcbAqiDiagnosticsService.diagnose(lat, lng, city, state));
    }
}
