package com.airsense.api.fusion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FusionProviderResponse {
    private String providerKey;
    private String providerName;
    @Builder.Default
    private FusionStatus status = FusionStatus.FAILED;
    @Builder.Default
    private String providerStatus = FusionStatus.FAILED.name();
    @Builder.Default
    private Instant timestamp = Instant.now();
    @Builder.Default
    private double confidence = 0.0;
    @Builder.Default
    private double providerConfidence = 0.0;
    @Builder.Default
    private long providerLatencyMs = 0L;
    @Builder.Default
    private boolean cached = false;
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    public static FusionProviderResponse success(String providerKey, Map<String, Object> data, double confidence) {
        return base(providerKey, FusionStatus.SUCCESS, data, confidence, List.of());
    }

    public static FusionProviderResponse partial(String providerKey, Map<String, Object> data, double confidence, List<String> errors) {
        return base(providerKey, FusionStatus.PARTIAL, data, confidence, errors);
    }

    public static FusionProviderResponse failure(String providerKey, String error) {
        return base(providerKey, FusionStatus.FAILED, Map.of(), 0.0, List.of(error));
    }

    private static FusionProviderResponse base(
            String providerKey,
            FusionStatus status,
            Map<String, Object> data,
            double confidence,
            List<String> errors) {
        return FusionProviderResponse.builder()
                .providerKey(providerKey)
                .providerName(providerKey)
                .status(status)
                .providerStatus(status.name())
                .timestamp(Instant.now())
                .confidence(confidence)
                .providerConfidence(confidence)
                .data(data != null ? new HashMap<>(data) : new HashMap<>())
                .errors(errors != null ? new ArrayList<>(errors) : new ArrayList<>())
                .build();
    }

    public FusionProviderResponse normalized(String key, String name, long latencyMs) {
        return toBuilder()
                .providerKey(providerKey != null ? providerKey : key)
                .providerName(providerName != null ? providerName : name)
                .status(status != null ? status : FusionStatus.FAILED)
                .providerStatus(status != null ? status.name() : FusionStatus.FAILED.name())
                .timestamp(timestamp != null ? timestamp : Instant.now())
                .confidence(confidence)
                .providerConfidence(providerConfidence > 0 ? providerConfidence : confidence)
                .providerLatencyMs(latencyMs)
                .data(sanitizeMap(data))
                .errors(errors != null ? errors : new ArrayList<>())
                .metadata(sanitizeMap(metadata))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null) {
            return new HashMap<>();
        }
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue()));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sanitized.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value;
    }
}
