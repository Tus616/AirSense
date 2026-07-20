package com.airsense.api.history;

import java.util.List;

public record CanonicalLocationIdentity(
        String locationKey,
        String keyVersion,
        String countryCode,
        String normalizedCountry,
        String normalizedState,
        String normalizedCity,
        Double latitude,
        Double longitude,
        Double roundedLatitude,
        Double roundedLongitude,
        List<String> legacyLocationKeys
) {
}
