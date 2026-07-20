package com.airsense.api.attribution;

public enum AttributionConfidenceLabel {
    HIGH,
    MEDIUM,
    LOW,
    INSUFFICIENT_EVIDENCE;

    public static AttributionConfidenceLabel from(double confidence) {
        if (confidence >= 0.75) return HIGH;
        if (confidence >= 0.50) return MEDIUM;
        if (confidence >= 0.25) return LOW;
        return INSUFFICIENT_EVIDENCE;
    }
}
