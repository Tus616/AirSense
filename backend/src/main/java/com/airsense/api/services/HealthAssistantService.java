package com.airsense.api.services;

import com.airsense.api.dto.AssistantRequestDto;
import com.airsense.api.dto.AssistantResponseDto;
import com.airsense.api.entities.HealthAssistantLog;
import com.airsense.api.entities.Prediction;
import com.airsense.api.repositories.HealthAssistantLogRepository;
import com.airsense.api.repositories.PredictionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class HealthAssistantService {

    @Autowired
    private GeminiClient geminiClient;

    @Autowired
    private PredictionRepository predictionRepository;

    @Autowired
    private HealthKnowledgeGraphService knowledgeGraphService;

    @Autowired
    private HealthAssistantLogRepository logRepository;

    @Value("${features.voice-assistant:true}")
    private boolean isFeatureEnabled;

    public AssistantResponseDto ask(AssistantRequestDto request, String userId) {
        if (!isFeatureEnabled) {
            return fallback("The Voice Assistant feature is currently disabled globally.");
        }

        // Gather Grounding Context
        int currentAqi = 150; // Fallback default
        String contextStr = "";

        if (request.getWardId() != null) {
            Optional<Prediction> predOpt = predictionRepository.findTopByWardIdOrderByGeneratedAtDesc(request.getWardId());
            if (predOpt.isPresent()) {
                Prediction pred = predOpt.get();
                if (!pred.getPredictions().isEmpty()) {
                    currentAqi = pred.getPredictions().get(0).getPredictedAqi();
                    contextStr = String.format("Current Ward AQI: %d (Category: %s). Trend next 24h: Peak AQI expected is %d.",
                            currentAqi, pred.getPredictions().get(0).getCategory(),
                            pred.getPredictions().stream().limit(24).mapToInt(p -> p.getPredictedAqi()).max().orElse(currentAqi));
                }
            }
        }

        String graphRules = knowledgeGraphService.getGuidanceForAqi(currentAqi, request.isVulnerable()).getGuidance();

        String prompt = String.format("""
                You are a helpful, empathetic local government health assistant for Delhi AQI.
                A citizen is asking a question: "%s"
                
                You must answer using strictly the following grounded facts. Do NOT invent medical advice.
                
                Context:
                %s
                
                %s
                
                Provide your response in JSON format exactly matching:
                {
                  "answer": "Your friendly, concise response to the user's question.",
                  "groundingSummary": "1 sentence summarizing the AQI data used",
                  "safetyNotes": "Medical disclaimer if required"
                }
                """, request.getQuestion(), contextStr, graphRules);

        try {
            JsonNode geminiRes = geminiClient.generateContent(prompt, true);
            
            AssistantResponseDto response = AssistantResponseDto.builder()
                    .answer(geminiRes.get("answer").asText())
                    .groundingSummary(geminiRes.has("groundingSummary") ? geminiRes.get("groundingSummary").asText() : "Real-time AQI and certified health guidelines.")
                    .safetyNotes(geminiRes.has("safetyNotes") ? geminiRes.get("safetyNotes").asText() : "Consult a doctor for medical advice.")
                    .isSimulated(false)
                    .source("gemini")
                    .build();

            logQuery(request, response, userId, contextStr);
            return response;

        } catch (Exception e) {
            e.printStackTrace();
            AssistantResponseDto fallback = fallback("Voice Assistant is currently running in offline/mock mode due to missing API key or temporary outage. " +
                    "To answer your question based on static rules: " + graphRules);
            logQuery(request, fallback, userId, "Static Offline Rules");
            return fallback;
        }
    }

    private AssistantResponseDto fallback(String message) {
        return AssistantResponseDto.builder()
                .answer(message)
                .groundingSummary("Offline simulated mode")
                .safetyNotes("Consult a doctor for medical advice.")
                .isSimulated(true)
                .source("fallback")
                .build();
    }

    private void logQuery(AssistantRequestDto req, AssistantResponseDto res, String userId, String grounding) {
        logRepository.save(HealthAssistantLog.builder()
                .wardId(req.getWardId())
                .userId(userId)
                .question(req.getQuestion())
                .answer(res.getAnswer())
                .groundingUsed(grounding)
                .simulatedFallback(res.isSimulated())
                .createdAt(Instant.now())
                .build());
    }
}
