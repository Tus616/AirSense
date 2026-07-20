package com.airsense.api.fusion;

import com.airsense.api.services.IqAirService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrimaryAqiSelectionService {
    private final CpcbAqiService cpcbAqiService;
    private final IqAirService iqAirService;
    private final CurrentAqiProperties properties;

    public Map<String, Object> select(FusionRequest request, double latitude, double longitude,
                                      Map<String, Object> openWeather, Object historicalAqi) {
        Map<String, Object> cpcb = cpcbAqiService.fetchCanonicalAqi(request, latitude, longitude);
        CpcbValidation cpcbValidation = validateCpcb(cpcb);
        Map<String, Object> iqAir = Map.of(
                "available", false,
                "usedAsFallback", false,
                "reason", "Not queried because CPCB was selected"
        );

        Map<String, Object> selected;
        if (cpcbValidation.usable()) {
            selected = selectedFromCpcb(cpcb, cpcbValidation.reason());
        } else {
            iqAir = iqAirService.getNearestCityAqi(latitude, longitude);
            selected = selectedFromIqAir(iqAir, cpcbValidation.reason());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", selected.get("currentAqi") instanceof Number);
        result.put("currentAqi", selected.get("currentAqi"));
        result.put("canonicalAqi", selected.get("currentAqi"));
        result.put("aqi", selected.get("currentAqi"));
        result.put("standard", selected.get("standard"));
        result.put("aqiStandard", standardLabel(selected.get("standard")));
        result.put("provider", selected.get("provider"));
        result.put("aqiProvider", selected.get("provider"));
        result.put("isFallback", selected.get("isFallback"));
        result.put("fallbackUsed", selected.get("isFallback"));
        result.put("fallbackReason", selected.get("fallbackReason"));
        result.put("selectionReason", selected.get("selectionReason"));
        result.put("primaryPollutant", selected.get("primaryPollutant"));
        result.put("prominentPollutant", selected.get("primaryPollutant"));
        result.put("aqiCategory", selected.get("category"));
        result.put("stationName", firstNonBlank(selected.get("stationName"), selected.get("providerReturnedStation"), selected.get("providerReturnedCity")));
        result.put("matchedCity", selected.get("matchedCity"));
        result.put("matchedState", selected.get("matchedState"));
        result.put("providerReturnedCity", selected.get("providerReturnedCity"));
        result.put("providerReturnedStation", selected.get("providerReturnedStation"));
        result.put("stationLatitude", selected.get("stationLatitude"));
        result.put("stationLongitude", selected.get("stationLongitude"));
        result.put("distanceKm", selected.get("distanceKm"));
        result.put("observedAt", selected.get("observedAt"));
        result.put("timestamp", selected.get("observedAt"));
        result.put("latestTimestamp", selected.get("observedAt"));
        result.put("lastUpdated", selected.get("observedAt"));
        result.put("fetchedAt", Instant.now().toString());
        result.put("freshnessStatus", selected.getOrDefault("freshnessStatus", "UNAVAILABLE"));
        result.put("sourceType", sourceType(selected));
        result.put("sourceLabel", sourceLabel(selected));
        result.put("providerStatus", selected.get("currentAqi") instanceof Number ? "SUCCESS" : "UNAVAILABLE");
        result.put("dataSource", dataSource(selected));
        result.put("dataOrigin", selected.get("currentAqi") instanceof Number ? "OBSERVED" : "UNAVAILABLE");
        result.put("location", location(request, latitude, longitude));
        result.put("selected", selected);
        result.put("cpcbEvidence", cpcbEvidence(cpcb, cpcbValidation));
        result.put("iqAirEvidence", iqAirEvidence(iqAir, selected));
        result.put("openWeatherEvidence", openWeatherEvidence(openWeather));
        result.put("openWeather", openWeather);
        result.put("openWeatherAqiIndex", openWeather != null ? openWeather.get("openWeatherAqiIndex") : null);
        result.put("openWeatherAqiScale", "OPENWEATHER_1_TO_5");
        result.put("openWeatherAqiCategory", openWeather != null ? openWeather.getOrDefault("openWeatherAqiCategory", "Unavailable") : "Unavailable");
        result.put("calculatedAqi", openWeather != null ? openWeather.get("calculatedAqi") : null);
        result.put("historicalAQI", historicalAqi);
        result.put("pollutants", pollutantMapFromCpcb(cpcb));
        result.put("pollutantBreakdown", cpcb.getOrDefault("pollutants", List.of()));
        result.put("pollutantsSource", Boolean.TRUE.equals(cpcb.get("available")) ? "CPCB_CAAQMS_SECONDARY_EVIDENCE" : "UNAVAILABLE");
        result.put("calculation", cpcb.getOrDefault("calculation", Map.of()));
        result.put("warnings", warnings(cpcbValidation, selected, openWeather));
        result.put("citySummary", computeCitySummary(cpcb, cpcbValidation));
        result.put("sourceScope", cpcbValidation.usable() ? "LOCAL_STATION" : (Boolean.TRUE.equals(iqAir.get("available")) ? "NEAREST_CITY" : "UNAVAILABLE"));
        result.put("reason", selected.get("currentAqi") instanceof Number ? "" : firstNonBlank(selected.get("fallbackReason"), cpcbValidation.reason()));
        result.put("snapshotId", SnapshotIdentity.snapshotId(latitude, longitude,
                Map.of("aqi", firstNonBlank(selected.get("observedAt"), result.get("fetchedAt"))),
                Map.of("aqi", String.valueOf(result.get("providerStatus")))));
        result.put("locationHash", SnapshotIdentity.locationHash(latitude, longitude));

        log.info("Primary AQI selected city={} state={} lat={} lon={} cpcbUsable={} cpcbReason={} cpcbStation={} cpcbDistanceKm={} cpcbObservedAt={} iqairCity={} iqairObservedAt={} provider={} standard={} currentAqi={}",
                request.getCityName(), request.getState(), latitude, longitude, cpcbValidation.usable(),
                cpcbValidation.reason(), cpcb.get("stationName"), cpcb.get("distanceKm"), cpcb.get("observedAt"),
                iqAir.get("city"), iqAir.get("observedAt"), selected.get("provider"), selected.get("standard"), selected.get("currentAqi"));
        return result;
    }

    private CpcbValidation validateCpcb(Map<String, Object> cpcb) {
        if (!Boolean.TRUE.equals(cpcb.get("available"))) {
            return new CpcbValidation(false, rejectionReason(String.valueOf(cpcb.getOrDefault("reason", "NO_CPCB_RECORD"))));
        }
        if (!(cpcb.get("aqi") instanceof Number) || ((Number) cpcb.get("aqi")).intValue() <= 0) {
            return new CpcbValidation(false, "INSUFFICIENT_POLLUTANTS");
        }
        if (!parseableInstant(cpcb.get("observedAt"))) {
            return new CpcbValidation(false, "INVALID_TIMESTAMP");
        }
        if ("STALE".equalsIgnoreCase(String.valueOf(cpcb.get("freshnessStatus")))) {
            return new CpcbValidation(false, "STALE_DATA");
        }
        if (cpcb.get("distanceKm") instanceof Number distance
                && distance.doubleValue() > properties.getCpcb().getMaxStationDistanceKm()) {
            return new CpcbValidation(false, "STATION_TOO_FAR");
        }
        List<Map<String, Object>> pollutants = listOfMaps(cpcb.get("pollutants"));
        long validCount = pollutants.stream().filter(row -> Boolean.TRUE.equals(row.get("valid"))).count();
        boolean hasPm = pollutants.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("valid")))
                .map(row -> String.valueOf(row.getOrDefault("normalizedPollutant", "")))
                .anyMatch(value -> "PM25".equals(value) || "PM10".equals(value));
        if (validCount < 3 || !hasPm) {
            return new CpcbValidation(false, "INSUFFICIENT_POLLUTANTS");
        }
        boolean invalidValue = pollutants.stream().anyMatch(this::invalidPollutantValue);
        if (invalidValue) {
            return new CpcbValidation(false, "INVALID_POLLUTANT_VALUES");
        }
        return new CpcbValidation(true, "Nearest fresh valid CPCB station");
    }

    private boolean invalidPollutantValue(Map<String, Object> row) {
        Object concentration = row.get("concentration");
        if (!(concentration instanceof Number number)) return false;
        double value = number.doubleValue();
        String pollutant = String.valueOf(row.getOrDefault("normalizedPollutant", ""));
        if (value < 0 || !Double.isFinite(value)) return true;
        if ("CO".equals(pollutant)) return value > 100_000;
        return value > 5_000;
    }

    private Map<String, Object> selectedFromCpcb(Map<String, Object> cpcb, String reason) {
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("currentAqi", cpcb.get("aqi"));
        selected.put("standard", "INDIA_NAQI");
        selected.put("provider", "CPCB_CAAQMS");
        selected.put("isFallback", false);
        selected.put("fallbackReason", null);
        selected.put("primaryPollutant", cpcb.get("prominentPollutant"));
        selected.put("category", cpcb.get("aqiCategory"));
        selected.put("stationName", cpcb.get("stationName"));
        selected.put("matchedCity", firstLookup(cpcb, "lookupCity"));
        selected.put("matchedState", firstLookup(cpcb, "lookupState"));
        selected.put("stationLatitude", cpcb.get("stationLatitude"));
        selected.put("stationLongitude", cpcb.get("stationLongitude"));
        selected.put("distanceKm", cpcb.get("distanceKm"));
        selected.put("observedAt", cpcb.get("observedAt"));
        selected.put("freshnessStatus", cpcb.get("freshnessStatus"));
        selected.put("selectionReason", reason);
        return selected;
    }

    private Map<String, Object> selectedFromIqAir(Map<String, Object> iqAir, String cpcbReason) {
        Map<String, Object> selected = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(iqAir.get("available"))) {
            selected.put("currentAqi", null);
            selected.put("standard", null);
            selected.put("provider", "UNAVAILABLE");
            selected.put("isFallback", false);
            selected.put("fallbackReason", "CPCB rejected: " + cpcbReason + "; IQAir unavailable: " + iqAir.getOrDefault("reason", "UNKNOWN"));
            selected.put("category", "UNAVAILABLE");
            selected.put("freshnessStatus", "UNAVAILABLE");
            selected.put("selectionReason", "No usable current AQI provider was available");
            return selected;
        }
        selected.put("currentAqi", iqAir.get("currentAqi"));
        selected.put("standard", "US_AQI");
        selected.put("provider", "IQAIR");
        selected.put("isFallback", true);
        selected.put("fallbackReason", cpcbReason);
        selected.put("primaryPollutant", null);
        selected.put("category", iqAir.get("aqiCategory"));
        selected.put("providerReturnedCity", iqAir.get("city"));
        selected.put("providerReturnedStation", iqAir.get("stationName"));
        selected.put("observedAt", iqAir.get("observedAt"));
        selected.put("freshnessStatus", iqAir.get("freshnessStatus"));
        selected.put("distanceKm", providerDistance(iqAir));
        selected.put("selectionReason", "IQAir used because no fresh valid CPCB station was available");
        return selected;
    }

    private Map<String, Object> cpcbEvidence(Map<String, Object> cpcb, CpcbValidation validation) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("available", Boolean.TRUE.equals(cpcb.get("available")));
        evidence.put("usable", validation.usable());
        evidence.put("stationName", cpcb.get("stationName"));
        evidence.put("matchedCity", firstLookup(cpcb, "lookupCity"));
        evidence.put("matchedState", firstLookup(cpcb, "lookupState"));
        evidence.put("distanceKm", cpcb.get("distanceKm"));
        evidence.put("observedAt", cpcb.get("observedAt"));
        evidence.put("calculatedIndianAqi", cpcb.get("aqi"));
        evidence.put("primaryPollutant", cpcb.get("prominentPollutant"));
        evidence.put("pollutants", cpcb.getOrDefault("pollutants", List.of()));
        evidence.put("calculation", cpcb.getOrDefault("calculation", Map.of()));
        evidence.put("stationCandidates", cpcb.getOrDefault("stations", List.of()));
        evidence.put("attemptedLookups", cpcb.getOrDefault("attemptedLookups", List.of()));
        evidence.put("rejectionReason", validation.usable() ? null : validation.reason());
        evidence.put("providerStatus", cpcb.getOrDefault("providerStatus", Boolean.TRUE.equals(cpcb.get("available")) ? "SUCCESS" : "UNAVAILABLE"));
        evidence.put("cacheStatus", cpcb.get("cacheStatus"));
        evidence.put("httpStatus", cpcb.get("httpStatus"));
        return evidence;
    }

    private Map<String, Object> iqAirEvidence(Map<String, Object> iqAir, Map<String, Object> selected) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("available", Boolean.TRUE.equals(iqAir.get("available")));
        evidence.put("usedAsFallback", Boolean.TRUE.equals(selected.get("isFallback")) && "IQAIR".equals(selected.get("provider")));
        evidence.put("aqius", iqAir.get("currentAqi"));
        evidence.put("standard", "US_AQI");
        evidence.put("providerReturnedCity", iqAir.get("city"));
        evidence.put("providerReturnedStation", iqAir.get("stationName"));
        evidence.put("observedAt", iqAir.get("observedAt"));
        evidence.put("distanceKm", providerDistance(iqAir));
        evidence.put("reason", iqAir.get("reason"));
        evidence.put("cacheStatus", iqAir.get("cacheStatus"));
        evidence.put("httpStatus", iqAir.get("httpStatus"));
        return evidence;
    }

    private Map<String, Object> openWeatherEvidence(Map<String, Object> openWeather) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("available", openWeather != null && Boolean.TRUE.equals(openWeather.get("available")));
        evidence.put("openWeatherAqiIndex", openWeather != null ? openWeather.get("openWeatherAqiIndex") : null);
        evidence.put("scale", "OPENWEATHER_1_TO_5");
        evidence.put("category", openWeather != null ? openWeather.get("openWeatherAqiCategory") : null);
        evidence.put("components", openWeatherComponents(openWeather));
        evidence.put("observedAt", openWeather != null ? openWeather.get("timestamp") : null);
        evidence.put("provider", "OpenWeather");
        evidence.put("reason", openWeather != null ? openWeather.get("reason") : "OpenWeather not queried");
        return evidence;
    }

    private Map<String, Object> openWeatherComponents(Map<String, Object> openWeather) {
        if (openWeather == null) return Map.of();
        Map<String, Object> components = new LinkedHashMap<>();
        for (String key : List.of("pm25", "pm10", "no2", "so2", "co", "o3", "nh3")) {
            if (openWeather.containsKey(key)) {
                components.put(key, openWeather.get(key));
            }
        }
        return components;
    }

    private Map<String, Object> location(FusionRequest request, double latitude, double longitude) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("searchedDisplayName", firstNonBlank(request.getCityName(), request.getCityId()));
        location.put("searchedCity", firstNonBlank(request.getCityName(), request.getCityId()));
        location.put("searchedState", request.getState());
        location.put("country", request.getCountry());
        location.put("latitude", latitude);
        location.put("longitude", longitude);
        return location;
    }

    private List<String> warnings(CpcbValidation cpcbValidation, Map<String, Object> selected, Map<String, Object> openWeather) {
        List<String> warnings = new java.util.ArrayList<>();
        if (!cpcbValidation.usable()) warnings.add("CPCB rejected: " + cpcbValidation.reason());
        if (Boolean.TRUE.equals(selected.get("isFallback"))) warnings.add("Primary AQI is IQAir US_AQI fallback, not Indian NAQI");
        if (openWeather == null || !Boolean.TRUE.equals(openWeather.get("available"))) warnings.add("OpenWeather supporting evidence unavailable");
        return warnings;
    }

    private String sourceType(Map<String, Object> selected) {
        if ("CPCB_CAAQMS".equals(selected.get("provider"))) return "CPCB_STATION_CALCULATED_INDIAN_NAQI";
        if ("IQAIR".equals(selected.get("provider"))) return "IQAIR_AIRVISUAL_OBSERVED_US_AQI";
        return "UNAVAILABLE";
    }

    private String sourceLabel(Map<String, Object> selected) {
        if ("CPCB_CAAQMS".equals(selected.get("provider"))) return "CPCB CAAQMS Indian NAQI";
        if ("IQAIR".equals(selected.get("provider"))) return "IQAir AirVisual US AQI fallback";
        return "Current AQI unavailable";
    }

    private String dataSource(Map<String, Object> selected) {
        if ("CPCB_CAAQMS".equals(selected.get("provider"))) return "cpcb_caaqms_calculated_indian_naqi";
        if ("IQAIR".equals(selected.get("provider"))) return "iqair_airvisual_nearest_city_fallback";
        return "unavailable";
    }

    private String standardLabel(Object standard) {
        if ("INDIA_NAQI".equals(standard)) return "Indian NAQI";
        if ("US_AQI".equals(standard)) return "US AQI";
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private Map<String, Object> pollutantMapFromCpcb(Map<String, Object> cpcb) {
        Map<String, Object> pollutants = new LinkedHashMap<>();
        for (Map<String, Object> row : listOfMaps(cpcb.get("pollutants"))) {
            String key = String.valueOf(row.getOrDefault("normalizedPollutant", row.getOrDefault("pollutant", ""))).toLowerCase(Locale.ROOT);
            if ("pm25".equals(key)) key = "pm25";
            if (!key.isBlank()) pollutants.put(key, row.get("concentration"));
        }
        return pollutants;
    }

    private Object firstLookup(Map<String, Object> cpcb, String key) {
        List<Map<String, Object>> stations = listOfMaps(cpcb.get("stations"));
        if (!stations.isEmpty() && stations.get(0).containsKey(key)) {
            return stations.get(0).get(key);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Object providerDistance(Map<String, Object> iqAir) {
        Object coordinates = iqAir.get("coordinates");
        Object providerCoordinates = iqAir.get("providerCoordinates");
        if (!(coordinates instanceof Map<?, ?> searched) || !(providerCoordinates instanceof Map<?, ?> returned)) return null;
        Object lat1 = ((Map<String, Object>) searched).get("latitude");
        Object lon1 = ((Map<String, Object>) searched).get("longitude");
        Object lat2 = ((Map<String, Object>) returned).get("latitude");
        Object lon2 = ((Map<String, Object>) returned).get("longitude");
        if (lat1 instanceof Number a && lon1 instanceof Number b && lat2 instanceof Number c && lon2 instanceof Number d) {
            return Math.round(distanceKm(a.doubleValue(), b.doubleValue(), c.doubleValue(), d.doubleValue()) * 1000.0) / 1000.0;
        }
        return null;
    }

    private double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private boolean parseableInstant(Object value) {
        try {
            Instant.parse(String.valueOf(value));
            return true;
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    private String rejectionReason(String reason) {
        String lower = reason != null ? reason.toLowerCase(Locale.ROOT) : "";
        if (lower.contains("stale")) return "STALE_DATA";
        if (lower.contains("timeout")) return "CPCB_TIMEOUT";
        if (lower.contains("configured") || lower.contains("unavailable")) return "CPCB_PROVIDER_ERROR";
        if (lower.contains("fresh") || lower.contains("station")) return "NO_MATCHING_STATION";
        return "NO_CPCB_RECORD";
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = value != null ? String.valueOf(value).trim() : "";
            if (!text.isBlank() && !"null".equalsIgnoreCase(text)) return text;
        }
        return "";
    }

    private record CpcbValidation(boolean usable, String reason) {
    }

    /**
     * Compute city-wide AQI summary from ALL fresh CPCB stations for the city.
     * Uses only LIVE stations with valid AQI values. Standard is always INDIA_NAQI.
     * This is separate from the selected local station AQI.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> computeCitySummary(Map<String, Object> cpcb, CpcbValidation cpcbValidation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", false);
        summary.put("standard", "INDIA_NAQI");
        summary.put("scope", "CITY");

        if (!cpcbValidation.usable() || cpcb == null) {
            summary.put("reason", "CPCB data not usable for city summary");
            return summary;
        }

        List<Map<String, Object>> stations = listOfMaps(cpcb.get("stations"));
        String selectedCity = firstNonBlank(cpcb.get("city"), cpcb.get("lookupCity"));
        List<Map<String, Object>> eligibleStations = stations.stream()
                .filter(s -> "CPCB_CAAQMS".equals(firstNonBlank(s.get("provider"), "CPCB_CAAQMS")))
                .filter(s -> "INDIA_NAQI".equals(firstNonBlank(s.get("standard"), s.get("aqiStandard"), "INDIA_NAQI")))
                .filter(s -> sameCity(selectedCity, firstNonBlank(s.get("city"), s.get("lookupCity"))))
                .filter(s -> "LIVE".equals(s.get("freshnessStatus")))
                .filter(s -> hasValidTimestamp(s.get("observedAt")))
                .filter(s -> s.get("aqi") instanceof Number)
                .filter(s -> ((Number) s.get("aqi")).intValue() > 0 && ((Number) s.get("aqi")).intValue() <= 500)
                .toList();
        List<Integer> freshAqiValues = eligibleStations.stream()
                .map(s -> ((Number) s.get("aqi")).intValue())
                .toList();

        int minStations = Math.max(1, properties.getCpcb().getCitySummaryMinStations());
        if (freshAqiValues.size() < minStations) {
            summary.put("reason", "Insufficient fresh CPCB city stations (" + freshAqiValues.size() + "/" + minStations + ")");
            return summary;
        }

        List<Integer> sorted = new ArrayList<>(freshAqiValues);
        Collections.sort(sorted);
        int count = sorted.size();
        double median;
        if (count % 2 == 0) {
            median = (sorted.get(count / 2 - 1) + sorted.get(count / 2)) / 2.0;
        } else {
            median = sorted.get(count / 2);
        }
        int medianAqi = (int) Math.round(median);
        int minAqi = sorted.get(0);
        int maxAqi = sorted.get(count - 1);

        List<String> stationNames = eligibleStations.stream()
                .map(s -> String.valueOf(s.getOrDefault("station", "Unknown")))
                .toList();

        summary.put("available", true);
        summary.put("medianAqi", medianAqi);
        summary.put("aqiCategory", category(medianAqi));
        summary.put("freshStationCount", count);
        summary.put("stationAqiRange", Map.of("min", minAqi, "max", maxAqi));
        summary.put("stationNames", stationNames);
        summary.put("stationAqiValues", sorted);
        summary.put("freshnessRule", "LIVE CPCB_CAAQMS INDIA_NAQI stations in same city with valid timestamp and AQI");
        summary.put("reason", "City summary from " + count + " fresh CPCB stations");
        return summary;
    }

    private boolean sameCity(String selectedCity, String stationCity) {
        if (selectedCity == null || selectedCity.isBlank()) return true;
        return selectedCity.equalsIgnoreCase(stationCity);
    }

    private boolean hasValidTimestamp(Object timestamp) {
        if (timestamp == null || String.valueOf(timestamp).isBlank()) return false;
        try {
            Instant.parse(String.valueOf(timestamp));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String category(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Satisfactory";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "Poor";
        if (aqi <= 400) return "Very Poor";
        return "Severe";
    }
}
