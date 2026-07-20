package com.airsense.api.forecast;

import java.util.Collection;

public enum ForecastFallbackReason {
    MODEL_NOT_PROMOTED,
    ARTIFACT_UNAVAILABLE,
    ML_SERVICE_UNAVAILABLE,
    FEATURE_SCHEMA_MISMATCH,
    OUT_OF_DISTRIBUTION_FEATURES,
    INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY,
    LIVE_HISTORY_STALE,
    LIVE_HISTORY_COVERAGE_LOW,
    LIVE_HISTORY_GAP_TOO_LARGE,
    CHECKSUM_MISMATCH,
    FALLBACK_ENGINE_USED;

    public static String publicValue(String reason) {
        if (reason == null || reason.isBlank()) {
            return FALLBACK_ENGINE_USED.name();
        }
        String normalized = reason.trim().toUpperCase();
        return switch (normalized) {
            case "MODEL_NOT_PROMOTED", "ML_RESPONSE_MISSING", "UNPROMOTED_MODEL" -> MODEL_NOT_PROMOTED.name();
            case "ARTIFACT_UNAVAILABLE", "ARTIFACT_MISSING", "MODEL_ARTIFACT_MISSING" -> ARTIFACT_UNAVAILABLE.name();
            case "ML_SERVICE_UNAVAILABLE", "ML_SERVICE_ERROR", "ML_SERVICE_TIMEOUT" -> ML_SERVICE_UNAVAILABLE.name();
            case "FEATURE_SCHEMA_MISMATCH" -> FEATURE_SCHEMA_MISMATCH.name();
            case "OUT_OF_DISTRIBUTION_FEATURES", "OUT_OF_DISTRIBUTION" -> OUT_OF_DISTRIBUTION_FEATURES.name();
            case "CHECKSUM_MISMATCH", "ARTIFACT_CHECKSUM_MISMATCH" -> CHECKSUM_MISMATCH.name();
            case "STALE_CURRENT_OBSERVATION" -> LIVE_HISTORY_STALE.name();
            case "INSUFFICIENT_TIME_COVERAGE" -> LIVE_HISTORY_COVERAGE_LOW.name();
            case "EXCESSIVE_DATA_GAPS" -> LIVE_HISTORY_GAP_TOO_LARGE.name();
            case "INSUFFICIENT_OBSERVATION_COUNT", "INSUFFICIENT_SAME_STANDARD_HISTORY" ->
                    INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY.name();
            default -> {
                if (normalized.contains("SCHEMA")) yield FEATURE_SCHEMA_MISMATCH.name();
                if (normalized.contains("OUT_OF_DISTRIBUTION") || normalized.contains("OOD")) yield OUT_OF_DISTRIBUTION_FEATURES.name();
                if (normalized.contains("CHECKSUM")) yield CHECKSUM_MISMATCH.name();
                if (normalized.contains("ARTIFACT")) yield ARTIFACT_UNAVAILABLE.name();
                if (normalized.contains("PROMOT")) yield MODEL_NOT_PROMOTED.name();
                if (normalized.contains("STALE")) yield LIVE_HISTORY_STALE.name();
                if (normalized.contains("GAP")) yield LIVE_HISTORY_GAP_TOO_LARGE.name();
                if (normalized.contains("COVERAGE")) yield LIVE_HISTORY_COVERAGE_LOW.name();
                if (normalized.contains("INSUFFICIENT")) yield INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY.name();
                yield FALLBACK_ENGINE_USED.name();
            }
        };
    }

    public static String publicValue(Collection<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return FALLBACK_ENGINE_USED.name();
        }
        if (reasons.stream().anyMatch(reason -> LIVE_HISTORY_STALE.name().equals(publicValue(reason)))) {
            return LIVE_HISTORY_STALE.name();
        }
        if (reasons.stream().anyMatch(reason -> LIVE_HISTORY_GAP_TOO_LARGE.name().equals(publicValue(reason)))) {
            return LIVE_HISTORY_GAP_TOO_LARGE.name();
        }
        if (reasons.stream().anyMatch(reason -> LIVE_HISTORY_COVERAGE_LOW.name().equals(publicValue(reason)))) {
            return LIVE_HISTORY_COVERAGE_LOW.name();
        }
        return reasons.stream()
                .map(ForecastFallbackReason::publicValue)
                .findFirst()
                .orElse(FALLBACK_ENGINE_USED.name());
    }
}
