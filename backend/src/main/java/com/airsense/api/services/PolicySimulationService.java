package com.airsense.api.services;

import com.airsense.api.dto.PolicySimulationRequestDto;
import com.airsense.api.entities.PolicySimulation;
import com.airsense.api.repositories.PolicySimulationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class PolicySimulationService {

    @Autowired
    private GeminiClient geminiClient;

    @Autowired
    private PolicySimulationRepository repository;

    @Value("${features.policy-simulation:true}")
    private boolean isFeatureEnabled;

    public PolicySimulation runSimulation(PolicySimulationRequestDto request, String adminId) {
        if (!isFeatureEnabled) {
            return fallback(request, adminId, "Policy Simulation feature is disabled globally.");
        }

        String prompt = String.format("""
                You are a Multi-Agent AI system simulating a policy intervention for Delhi.
                Scenario: "%s"
                Ward: "%s"
                
                Act as 3 agents and debate the outcome. Return strictly JSON in this exact format:
                {
                  "agentDebate": [
                    { "agentRole": "AirQualityAgent", "perspective": "...", "impactScore": "+5" },
                    { "agentRole": "TrafficEconomicsAgent", "perspective": "...", "impactScore": "-3" },
                    { "agentRole": "PublicHealthAgent", "perspective": "...", "impactScore": "+4" }
                  ],
                  "recommendedAction": "Summary of final recommendation",
                  "riskTradeoffs": "Key risks to mitigate"
                }
                """, request.getScenario(), request.getWardId() != null ? request.getWardId() : "Citywide");

        try {
            JsonNode geminiRes = geminiClient.generateContent(prompt, true);
            
            List<PolicySimulation.AgentResponse> debate = new ArrayList<>();
            if (geminiRes.has("agentDebate") && geminiRes.get("agentDebate").isArray()) {
                for (JsonNode node : geminiRes.get("agentDebate")) {
                    debate.add(PolicySimulation.AgentResponse.builder()
                            .agentRole(node.get("agentRole").asText())
                            .perspective(node.get("perspective").asText())
                            .impactScore(node.get("impactScore").asText())
                            .build());
                }
            }

            PolicySimulation simulation = PolicySimulation.builder()
                    .scenario(request.getScenario())
                    .wardId(request.getWardId())
                    .agentDebate(debate)
                    .recommendedAction(geminiRes.has("recommendedAction") ? geminiRes.get("recommendedAction").asText() : "Proceed with caution")
                    .riskTradeoffs(geminiRes.has("riskTradeoffs") ? geminiRes.get("riskTradeoffs").asText() : "Monitor local economic impact")
                    .createdBy(adminId)
                    .createdAt(Instant.now())
                    .isSimulated(false)
                    .source("gemini")
                    .build();

            return repository.save(simulation);

        } catch (Exception e) {
            e.printStackTrace();
            return fallback(request, adminId, "Offline simulated mock response. The Gemini API is currently unavailable or unconfigured.");
        }
    }

    private PolicySimulation fallback(PolicySimulationRequestDto req, String adminId, String message) {
        List<PolicySimulation.AgentResponse> debate = List.of(
                PolicySimulation.AgentResponse.builder().agentRole("AirQualityAgent").perspective("Will reduce PM2.5 by 15%").impactScore("+5").build(),
                PolicySimulation.AgentResponse.builder().agentRole("TrafficEconomicsAgent").perspective("High disruption to logistics").impactScore("-4").build(),
                PolicySimulation.AgentResponse.builder().agentRole("PublicHealthAgent").perspective("Marginal immediate health benefit").impactScore("+2").build()
        );

        PolicySimulation sim = PolicySimulation.builder()
                .scenario(req.getScenario())
                .wardId(req.getWardId())
                .agentDebate(debate)
                .recommendedAction("Mock recommendation: " + message)
                .riskTradeoffs("Mock tradeoffs: High economic disruption.")
                .createdBy(adminId)
                .createdAt(Instant.now())
                .isSimulated(true)
                .source("fallback")
                .build();
        
        return repository.save(sim);
    }

    public List<PolicySimulation> getAllSimulations() {
        return repository.findAllByOrderByCreatedAtDesc();
    }
}
