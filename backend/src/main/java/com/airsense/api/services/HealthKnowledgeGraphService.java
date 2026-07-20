package com.airsense.api.services;

import com.airsense.api.dto.KnowledgeGraphResponseDto;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Provides static, verified health guidance to ground the LLM in safe facts.
 */
@Service
public class HealthKnowledgeGraphService {

    public KnowledgeGraphResponseDto getGuidanceForAqi(int aqi, boolean isVulnerable) {
        StringBuilder guidance = new StringBuilder("Verified Health Facts:\n");
        
        if (aqi <= 50) {
            guidance.append("- Air quality is good. No precautions necessary.\n");
        } else if (aqi <= 100) {
            guidance.append("- Air quality is satisfactory.\n");
            if (isVulnerable) guidance.append("- Sensitive individuals should consider reducing prolonged outdoor exertion.\n");
        } else if (aqi <= 200) {
            guidance.append("- Air quality is moderate.\n");
            guidance.append("- General public: Minor breathing discomfort possible.\n");
            if (isVulnerable) guidance.append("- Sensitive individuals: Avoid heavy outdoor exertion. Carry rescue inhalers if asthmatic.\n");
        } else if (aqi <= 300) {
            guidance.append("- Air quality is poor.\n");
            guidance.append("- General public: Wear masks outdoors. Avoid heavy physical exertion.\n");
            if (isVulnerable) guidance.append("- Sensitive individuals: Stay indoors. Keep windows closed.\n");
        } else {
            guidance.append("- Air quality is severe.\n");
            guidance.append("- General public: Avoid all outdoor physical activity. Wear N95 masks if outdoor travel is unavoidable.\n");
            if (isVulnerable) guidance.append("- Sensitive individuals: Remain indoors. Use air purifiers if available. Seek immediate medical attention if experiencing breathing difficulty.\n");
        }

        guidance.append("\nDisclaimer to include: This assistant provides general guidance based on AQI. It does not provide medical diagnoses. Consult a doctor for medical advice.");
        
        return KnowledgeGraphResponseDto.builder()
                .guidance(guidance.toString())
                .source("fallback")
                .build();
    }
}
