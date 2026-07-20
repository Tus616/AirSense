package com.airsense.api.controllers;

import com.airsense.api.copilot.CopilotRequest;
import com.airsense.api.copilot.CopilotResponse;
import com.airsense.api.copilot.DecisionCopilotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/intelligence/copilot")
public class IntelligenceCopilotController {
    private final DecisionCopilotService copilotService;

    @PostMapping("/query")
    public ResponseEntity<CopilotResponse> query(@RequestBody CopilotRequest request) {
        return ResponseEntity.ok(copilotService.answer(request));
    }
}
