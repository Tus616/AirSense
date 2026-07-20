package com.airsense.api.controllers;

import com.airsense.api.forecast.ForecastModelRollbackService;
import com.airsense.api.forecast.ForecastRetrainingScheduler;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/forecast-models")
public class ForecastModelAdminController {
    private final ForecastModelRollbackService rollbackService;
    private final ForecastRetrainingScheduler retrainingScheduler;

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@RequestBody RollbackRequest request) {
        return ResponseEntity.ok(rollbackService.rollback(
                request.getAqiStandard(),
                request.getHorizonHours(),
                request.getModelScope(),
                request.getTargetVersion()
        ));
    }

    @GetMapping("/retraining/status")
    public ResponseEntity<Map<String, Object>> retrainingStatus() {
        return ResponseEntity.ok(retrainingScheduler.status());
    }

    @Data
    public static class RollbackRequest {
        private String aqiStandard;
        private Integer horizonHours;
        private String modelScope;
        private String targetVersion;
    }
}
