package com.airsense.api.fusion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

public final class SnapshotIdentity {
    private SnapshotIdentity() {
    }

    public static String locationHash(Double latitude, Double longitude) {
        if (latitude == null || longitude == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return "loc-unknown";
        }
        return "loc-" + sha256(normalizedCoordinatePair(latitude, longitude)).substring(0, 12);
    }

    public static String locationHash(double latitude, double longitude) {
        return locationHash(Double.valueOf(latitude), Double.valueOf(longitude));
    }

    public static String normalizedLatitude(Double latitude) {
        return latitude != null && Double.isFinite(latitude) ? String.format(Locale.ROOT, "%.5f", latitude) : "";
    }

    public static String normalizedLongitude(Double longitude) {
        return longitude != null && Double.isFinite(longitude) ? String.format(Locale.ROOT, "%.5f", longitude) : "";
    }

    public static String snapshotId(Double latitude, Double longitude, Map<String, Object> providerTimestamps,
                                    Map<String, String> providerStatus) {
        String raw = String.join("|",
                normalizedLatitude(latitude),
                normalizedLongitude(longitude),
                String.valueOf(providerTimestamps != null ? providerTimestamps : Map.of()),
                String.valueOf(providerStatus != null ? providerStatus : Map.of()));
        return "snap-" + sha256(raw).substring(0, 16);
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(raw).hashCode());
        }
    }

    private static String normalizedCoordinatePair(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.5f,%.5f", latitude, longitude);
    }
}
