package com.airsense.api.forecast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class MlForecastClient {
    private final RestTemplateBuilder restTemplateBuilder;
    private final MlForecastProperties properties;

    public MlForecastClient(RestTemplateBuilder restTemplateBuilder, MlForecastProperties properties) {
        this.restTemplateBuilder = restTemplateBuilder;
        this.properties = properties;
    }

    public Optional<MlForecastResponse> predict(MlForecastRequest request) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        RestTemplate restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
        try {
            String serviceUrl = normalizeServiceUrl(properties.getServiceUrl());
            ResponseEntity<MlForecastResponse> response = restTemplate.postForEntity(
                    serviceUrl + "/internal/forecast/predict",
                    request,
                    MlForecastResponse.class
            );
            return Optional.ofNullable(response.getBody());
        } catch (Exception e) {
            log.warn("ML forecast service unavailable snapshotId={} locationKey={} reason={}",
                    request.getSnapshotId(), request.getLocationKey(), e.getMessage());
            return Optional.empty();
        }
    }

    private String normalizeServiceUrl(String serviceUrl) {
        String resolved = serviceUrl == null ? "" : serviceUrl.trim();
        while (resolved.endsWith("/")) {
            resolved = resolved.substring(0, resolved.length() - 1);
        }
        return resolved;
    }

    @Data
    @Builder
    
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MlForecastRequest {
        private String snapshotId;
        private String locationKey;
        private String searchedLocationKey;
        private String stationLocationKey;
        private String stationKey;
        private String forecastScope;
        @Builder.Default
        private List<String> candidateModelScopes = new ArrayList<>();
        private String stationName;
        private Double stationLatitude;
        private Double stationLongitude;
        private String forecastStandard;
        private String aqiStandard;
        private String provider;
        private Double latitude;
        private Double longitude;
        private String forecastIssueTime;
        private String providerObservedAt;
        private Integer currentAqi;
        @Builder.Default
        private List<Integer> horizons = List.of(24, 48, 72);
        @Builder.Default
        private Map<String, Object> features = new HashMap<>();
        @Builder.Default
        private List<Map<String, Object>> history = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MlForecastResponse {
        private String snapshotId;
        private String locationKey;
        private String forecastStandard;
        private String generatedAt;
        private List<MlForecastPrediction> predictions = List.of();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MlForecastPrediction {
        private String status;
        private Integer horizonHours;
        private Integer predictedAqi;
        private Double predictedDelta;
        private Double unclampedPredictedAqi;
        private Integer lowerBound;
        private Integer upperBound;
        private String engine;
        private String forecastScope;
        private String modelScope;
        private String modelFamily;
        private String modelVersion;
        private Double confidence;
        private String confidenceLabel;
        private Integer baselinePredictedAqi;
        private Double validationRmse;
        private Double baselineRmse;
        private String fallbackReason;
        private String provider;
        private String dataOrigin;
        private String searchedLocationKey;
        private String locationKey;
        private String aqiStandard;
        private String stationKey;
        private String stationLocationKey;
        private String modelPromotionStatus;
        private Map<String, Object> trainingDeltaPercentiles;
        private String oodStatus;
        private Double oodScore;
        private String oodLevel;
        private List<String> oodFeatures;
        private List<String> warnings;
        private Map<String, Object> featureDiagnostics;
        private Map<String, Object> modelContributions;
    }
}
