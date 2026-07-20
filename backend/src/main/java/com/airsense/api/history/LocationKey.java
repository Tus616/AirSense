package com.airsense.api.history;

import java.util.Locale;

public final class LocationKey {
    private LocationKey() {
    }

    public static String of(String country, String city, Double latitude, Double longitude) {
        String safeCountry = slug(country == null || country.isBlank() ? "unknown" : country);
        String safeCity = slug(city == null || city.isBlank() ? "location" : city);
        String lat = latitude != null && Double.isFinite(latitude) ? String.format(Locale.ROOT, "%.3f", latitude) : "na";
        String lon = longitude != null && Double.isFinite(longitude) ? String.format(Locale.ROOT, "%.3f", longitude) : "na";
        return safeCountry + ":" + safeCity + ":" + lat + ":" + lon;
    }

    public static String slug(String value) {
        return String.valueOf(value == null ? "" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
