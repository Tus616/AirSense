package com.airsense.api.history;

import com.airsense.api.config.AirQualityOperationsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CanonicalLocationIdentityService {
    private final AirQualityOperationsProperties properties;

    @PostConstruct
    void validateConfiguration() {
        int precision = properties.getLocationCoordinatePrecision();
        if (precision < 2 || precision > 6) {
            throw new IllegalStateException("air-quality.location-coordinate-precision must be between 2 and 6");
        }
        if (!"v2".equals(properties.getLocationKeyVersion())) {
            log.warn("Non-default AQI location key version configured: {}", properties.getLocationKeyVersion());
        }
        if (precision != 3) {
            log.warn("AQI location coordinate precision is {}; changing precision changes canonical location identity.", precision);
        }
    }

    public CanonicalLocationIdentity identity(String city, String state, String country, Double latitude, Double longitude) {
        String countryCode = countryCode(country);
        Double roundedLatitude = round(latitude);
        Double roundedLongitude = round(longitude);
        String locationKey = countryCode + ":" + coordinate(roundedLatitude) + ":" + coordinate(roundedLongitude);
        String normalizedCountry = normalize(country == null || country.isBlank() ? countryCode : country);
        String normalizedState = normalize(state);
        String normalizedCity = normalize(city);
        return new CanonicalLocationIdentity(
                locationKey,
                properties.getLocationKeyVersion(),
                countryCode,
                normalizedCountry,
                normalizedState,
                normalizedCity,
                latitude,
                longitude,
                roundedLatitude,
                roundedLongitude,
                legacyKeys(country, city, state, latitude, longitude, roundedLatitude, roundedLongitude, locationKey)
        );
    }

    public List<String> queryKeys(CanonicalLocationIdentity identity) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(identity.locationKey());
        keys.addAll(identity.legacyLocationKeys());
        return new ArrayList<>(keys);
    }

    public String normalizeText(String value) {
        return normalize(value);
    }

    private List<String> legacyKeys(String country, String city, String state, Double latitude, Double longitude,
                                    Double roundedLatitude, Double roundedLongitude, String canonical) {
        Set<String> keys = new LinkedHashSet<>();
        addLegacy(keys, country, city, roundedLatitude, roundedLongitude);
        addLegacy(keys, country, state, roundedLatitude, roundedLongitude);
        addLegacy(keys, "India", city, roundedLatitude, roundedLongitude);
        addLegacy(keys, "IN", city, roundedLatitude, roundedLongitude);
        if (latitude != null && longitude != null) {
            addRawLegacy(keys, country, city, latitude, longitude);
            addRawLegacy(keys, "India", city, latitude, longitude);
        }
        keys.remove(canonical);
        keys.remove("");
        return new ArrayList<>(keys);
    }

    private void addLegacy(Set<String> keys, String country, String city, Double latitude, Double longitude) {
        String safeCountry = LocationKey.slug(country == null || country.isBlank() ? "unknown" : country);
        String safeCity = LocationKey.slug(city == null || city.isBlank() ? "location" : city);
        keys.add(safeCountry + ":" + safeCity + ":" + coordinate(latitude) + ":" + coordinate(longitude));
    }

    private void addRawLegacy(Set<String> keys, String country, String city, Double latitude, Double longitude) {
        String safeCountry = country == null || country.isBlank() ? "unknown" : country.trim();
        String safeCity = city == null || city.isBlank() ? "location" : city.trim();
        keys.add(safeCountry + ":" + safeCity + ":" + rawCoordinate(latitude) + ":" + rawCoordinate(longitude));
    }

    private Double round(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value)
                .setScale(properties.getLocationCoordinatePrecision(), RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String coordinate(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return "na";
        }
        return BigDecimal.valueOf(value)
                .setScale(properties.getLocationCoordinatePrecision(), RoundingMode.HALF_UP)
                .toPlainString();
    }

    private String rawCoordinate(Double value) {
        return value == null || !Double.isFinite(value) ? "na" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private String countryCode(String country) {
        String normalized = normalize(country);
        if (normalized.isBlank() || "india".equals(normalized) || "in".equals(normalized)) {
            return "in";
        }
        if (normalized.length() == 2) {
            return normalized;
        }
        return normalized.length() > 6 ? normalized.substring(0, 6) : normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized;
    }
}
