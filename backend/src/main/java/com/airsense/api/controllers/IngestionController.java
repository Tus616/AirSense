package com.airsense.api.controllers;

import com.airsense.api.ingestion.DataIngestionOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ingestion")
public class IngestionController {

    @Autowired
    private DataIngestionOrchestrator orchestrator;

    @PostMapping("/run")
    public ResponseEntity<?> runIngestion(@RequestParam(defaultValue = "all") String source) {
        // Run asynchronously to not block the HTTP request
        new Thread(() -> orchestrator.runIngestion(source)).start();
        
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Ingestion pipeline triggered for source: " + source
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(Map.of(
                "status", "active",
                "lastRun", "Data available in MongoDB"
        ));
    }
}
