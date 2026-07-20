package com.airsense.api.controllers;

import com.airsense.api.dto.AssistantRequestDto;
import com.airsense.api.dto.AssistantResponseDto;
import com.airsense.api.services.HealthAssistantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/citizen/assistant")
public class CitizenAssistantController {

    @Autowired
    private HealthAssistantService healthAssistantService;

    @PostMapping("/ask")
    public ResponseEntity<AssistantResponseDto> askAssistant(@RequestBody AssistantRequestDto request) {
        // In a real app, userId comes from JWT context
        String dummyUserId = "anonymous-citizen";
        return ResponseEntity.ok(healthAssistantService.ask(request, dummyUserId));
    }
}
