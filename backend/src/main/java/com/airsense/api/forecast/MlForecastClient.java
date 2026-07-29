package com.airsense.api.forecast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class MlForecastClient {
    private final RestTemplateBuilder restTemplateBuilder;
    private final MlForecastProperties properties;
    private volatile MlForecastTrace lastTrace = MlForecastTrace.empty();

    public MlForecastClient(RestTemplateBuilder restTemplateBuilder, MlForecastProperties properties) {
        this.restTemplateBuilder = restTemplateBuilder;
        this.properties = properties;
    }

    public Optional<MlForecastResponse> predict(MlForecastRequest request) {
        if (!properties.isEnabled()) {
            lastTrace = MlForecastTrace.skipped("ML_FORECAST_DISABLED", properties.getServiceUrl());
            return Optional.empty();
        }
        RestTemplate restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
        try {
            String serviceUrl = normalizeServiceUrl(properties.getServiceUrl());
            log.info("ML forecast request snapshotId={} lat={} lon={} currentAqi={} standard={} horizons={} stationKey={} serviceUrl={}",
                    request.getSnapshotId(), request.getLatitude(), request.getLongitude(), request.getCurrentAqi(),
                    request.getForecastStandard(), request.getHorizons(), request.getStationKey(), serviceUrl);
            ResponseEntity<MlForecastResponse> response = restTemplate.postForEntity(
                    serviceUrl + "/internal/forecast/predict",
                    request,
                    MlForecastResponse.class
            );
            MlForecastResponse body = response.getBody();
            lastTrace = MlForecastTrace.success(serviceUrl, response.getStatusCode().value(), body);
            log.info("ML forecast response snapshotId={} status={} engines={} predictedAqi={}",
                    request.getSnapshotId(), response.getStatusCode().value(), lastTrace.getEnginesByHorizon(), lastTrace.getPredictedAqiByHorizon());
            return Optional.ofNullable(body);
        } catch (HttpStatusCodeException e) {
            lastTrace = MlForecastTrace.failed(properties.getServiceUrl(), e.getStatusCode().value(), "HTTP_" + e.getStatusCode().value(), e.getResponseBodyAsString());
            log.warn("ML forecast HTTP failure snapshotId={} locationKey={} status={} reason={}",
                    request.getSnapshotId(), request.getLocationKey(), e.getStatusCode().value(), safeMessage(e.getResponseBodyAsString()));
            return Optional.empty();
        } catch (ResourceAccessException e) {
            lastTrace = MlForecastTrace.failed(properties.getServiceUrl(), null, "TIMEOUT_OR_CONNECTION_FAILURE", e.getMessage());
            log.warn("ML forecast connection failure snapshotId={} locationKey={} reason={}",
                    request.getSnapshotId(), request.getLocationKey(), safeMessage(e.getMessage()));
            return Optional.empty();
        } catch (RestClientException e) {
            lastTrace = MlForecastTrace.failed(properties.getServiceUrl(), null, "DESERIALIZATION_OR_CLIENT_FAILURE", e.getMessage());
            log.warn("ML forecast client failure snapshotId={} locationKey={} reason={}",
                    request.getSnapshotId(), request.getLocationKey(), safeMessage(e.getMessage()));
            return Optional.empty();
        }
    }
    
    public MlForecastTrace lastTrace() {
        return lastTrace;
    }

    private String normalizeServiceUrl(String serviceUrl) {
        String resolved = serviceUrl == null ? "" : serviceUrl.trim();
        while (resolved.endsWith("/")) {
            resolved = resolved.substring(0, resolved.length() - 1);
        }
        return resolved;
    }

    private String safeMessage(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replaceAll("(?i)(api[_-]?key|token|secret|password)=([^&\\s]+)", "$1=REDACTED");
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
        private String promotionStatus;
        private String targetTime;
        private Integer historyObservationCount;
        private Double historyCoverageHours;
        private Double featureCoveragePercent;
        private Map<String, Object> trainingDeltaPercentiles;
        private String oodStatus;
        private Double oodScore;
        private String oodLevel;
        private List<String> oodFeatures;
        private List<String> warnings;
        private Map<String, Object> featureDiagnostics;
        private Map<String, Object> modelContributions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MlForecastTrace {
        private String timestamp;
        private String serviceUrl;
        private Integer httpStatus;
        private boolean deserializationSuccess;
        private String failureType;
        private String failureReason;
        private Map<Integer, String> enginesByHorizon = new LinkedHashMap<>();
        private Map<Integer, Integer> predictedAqiByHorizon = new LinkedHashMap<>();

        static MlForecastTrace empty() {
            return new MlForecastTrace(Instant.now().toString(), "", null, false, "NOT_CALLED", "", new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        static MlForecastTrace skipped(String reason, String serviceUrl) {
            return new MlForecastTrace(Instant.now().toString(), serviceUrl, null, false, "SKIPPED", reason, new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        static MlForecastTrace failed(String serviceUrl, Integer status, String type, String reason) {
            return new MlForecastTrace(Instant.now().toString(), serviceUrl, status, false, type, reason, new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        static MlForecastTrace success(String serviceUrl, Integer status, MlForecastResponse response) {
            Map<Integer, String> engines = new LinkedHashMap<>();
            Map<Integer, Integer> values = new LinkedHashMap<>();
            if (response != null && response.getPredictions() != null) {
                for (MlForecastPrediction prediction : response.getPredictions()) {
                    if (prediction.getHorizonHours() != null) {
                        engines.put(prediction.getHorizonHours(), prediction.getEngine());
                        values.put(prediction.getHorizonHours(), prediction.getPredictedAqi());
                    }
                }
            }
            return new MlForecastTrace(Instant.now().toString(), serviceUrl, status, true, null, null, engines, values);
        }
    }
}
