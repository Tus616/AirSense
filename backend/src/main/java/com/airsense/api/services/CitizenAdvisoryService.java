package com.airsense.api.services;

import com.airsense.api.entities.CitizenRiskAdvisory;
import com.airsense.api.repositories.CitizenRiskAdvisoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class CitizenAdvisoryService {

    @Autowired
    private GeminiClient geminiClient;

    @Autowired
    private CitizenRiskAdvisoryRepository repository;

    private final ObjectMapper mapper = new ObjectMapper();

    public CitizenRiskAdvisory generateAdvisory(
            String userId, String cityId, String wardId, String language,
            int currentAqi, int peakAqi, String peakTime,
            double wardRiskScore, double personalRiskScore, String riskLevel,
            Map<String, Object> vulnFactors) {

        String prompt = String.format(
            "Generate a citizen health risk advisory in JSON format.\n" +
            "Language requested: %s\n" +
            "Context:\n" +
            "- Current AQI: %d\n" +
            "- Forecast Peak: %d AQI at %s\n" +
            "- Ward Risk Score: %.1f/100\n" +
            "- Personal Risk Score: %.1f/100\n" +
            "- Overall Risk Level: %s\n" +
            "- Vulnerabilities: %s\n\n" +
            "Rules:\n" +
            "1. Output ONLY valid JSON, nothing else.\n" +
            "2. Ensure the response is natively written in the requested language (e.g. if 'hi', write in Hindi text). If the language is unknown, default to English.\n" +
            "3. Ground the advisory strictly in the provided data. Do not invent numbers.\n" +
            "4. Keep it simple, factual, and public-health safe.\n\n" +
            "Required JSON format:\n" +
            "{\n" +
            "  \"language\": \"%s\",\n" +
            "  \"topLine\": \"Short alert summary\",\n" +
            "  \"advisory\": \"Detailed paragraph explaining the risk\",\n" +
            "  \"actions\": [\"Action 1\", \"Action 2\"],\n" +
            "  \"ivrScript\": \"Short script suitable for text-to-speech phone call\"\n" +
            "}",
            language, currentAqi, peakAqi, peakTime, wardRiskScore, personalRiskScore, riskLevel, vulnFactors, language
        );

        CitizenRiskAdvisory result = CitizenRiskAdvisory.builder()
            .userId(userId)
            .cityId(cityId)
            .wardId(wardId)
            .language(language)
            .generatedAt(Instant.now().toString())
            .currentAqi(currentAqi)
            .forecastPeakAqi(peakAqi)
            .forecastPeakAt(peakTime)
            .wardRiskScore(wardRiskScore)
            .personalRiskScore(personalRiskScore)
            .riskLevel(riskLevel)
            .vulnerabilityFactors(vulnFactors)
            .build();

        try {
            com.fasterxml.jackson.databind.JsonNode parsed = geminiClient.generateContent(prompt, true);
            
            result.setTopLine(parsed.path("topLine").asText("Air Quality Alert"));
            result.setAdvisory(parsed.path("advisory").asText("Please take precautions based on current air quality."));
            
            List<String> actionsList = Arrays.asList("Stay indoors if sensitive", "Wear a mask outside");
            if (parsed.has("actions") && parsed.get("actions").isArray()) {
                actionsList = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode n : parsed.get("actions")) {
                    actionsList.add(n.asText());
                }
            }
            result.setActions(actionsList);
            
            result.setIvrScript(parsed.path("ivrScript").asText("This is an air quality alert. Please stay indoors."));
            result.setSource("gemini");
            
        } catch (Exception e) {
            log.error("Failed to generate Gemini advisory, using fallback", e);
            applyFallback(result, language, riskLevel, peakAqi);
        }

        return repository.save(result);
    }

    private void applyFallback(CitizenRiskAdvisory result, String lang, String riskLevel, int peakAqi) {
        result.setSource("fallback");
        if ("hi".equalsIgnoreCase(lang)) {
            result.setTopLine("वायु गुणवत्ता चेतावनी (Air Quality Alert)");
            result.setAdvisory("वर्तमान वायु गुणवत्ता के आधार पर कृपया सावधानी बरतें। (Please take precautions based on current air quality.)");
            result.setActions(Arrays.asList("संवेदनशील होने पर घर के अंदर रहें", "बाहर मास्क पहनें"));
            result.setIvrScript("यह वायु गुणवत्ता अलर्ट है। कृपया घर के अंदर रहें।");
        } else {
            result.setTopLine("Air Quality Alert: " + riskLevel);
            result.setAdvisory("The air quality is forecasted to reach " + peakAqi + " AQI. Please exercise caution.");
            result.setActions(Arrays.asList("Limit outdoor exertion", "Keep windows closed"));
            result.setIvrScript("This is an automated alert. Air quality is currently " + riskLevel + ". Take care.");
        }
    }
}
