package com.airsense.api.services;

import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.GridForecast;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "legacy.forecast", name = "enabled", havingValue = "true")
public class ForecastClient {

    @Value("${ai.service.base-url:http://localhost:8000}")
    private String aiServiceBaseUrl;

    private final RestTemplate restTemplate;

    public ForecastClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))  // predictions can take a while
                .build();
    }

    /**
     * Single-sensor prediction (kept for backward compatibility).
     */
    public Prediction fetchForecast(String wardId, String sensorId) {
        String url = aiServiceBaseUrl + "/predict";

        Map<String, String> request = new HashMap<>();
        request.put("wardId", wardId);
        request.put("sensorId", sensorId);

        try {
            ResponseEntity<Prediction> response = restTemplate.postForEntity(url, request, Prediction.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch forecast from AI service for sensor {}: {}", sensorId, e.getMessage());
            return null;
        }
    }

    /**
     * Batch prediction — sends all sensor/ward pairs in a single HTTP call
     * to the AI service's /predict-batch endpoint.
     *
     * @param sensorPairs list of maps with keys "wardId" and "sensorId"
     * @return list of Prediction objects (may be smaller than input if some fail)
     */
    public List<Prediction> fetchBatchForecasts(List<Map<String, String>> sensorPairs) {
        String url = aiServiceBaseUrl + "/predict-batch";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("sensors", sensorPairs);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<List<Prediction>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<List<Prediction>>() {}
            );
            List<Prediction> predictions = response.getBody();
            log.info("Batch forecast returned {} predictions for {} requested sensors",
                    predictions != null ? predictions.size() : 0, sensorPairs.size());
            return predictions;
        } catch (Exception e) {
            log.error("Failed to fetch batch forecasts from AI service: {}", e.getMessage());
            return List.of();
        }
    }

    public List<GridForecast> fetchBatchGridForecasts(List<Map<String, String>> gridPairs) {
        String url = aiServiceBaseUrl + "/predict-batch";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("sensors", gridPairs);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<List<GridForecast>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<List<GridForecast>>() {}
            );
            List<GridForecast> predictions = response.getBody();
            log.info("Batch grid forecast returned {} predictions for {} requested grids",
                    predictions != null ? predictions.size() : 0, gridPairs.size());
            return predictions;
        } catch (Exception e) {
            log.error("Failed to fetch batch grid forecasts from AI service: {}", e.getMessage());
            return List.of();
        }
    }
}
