package com.airsense.api.attribution;

import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import com.airsense.api.fusion.SnapshotIdentity;
import com.airsense.api.history.CanonicalLocationIdentity;
import com.airsense.api.history.CanonicalLocationIdentityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PollutionAttributionService {
    private static final int CACHE_MAX_ENTRIES = 256;

    @Autowired(required = false)
    private DataFusionService dataFusionService;
    @Autowired(required = false)
    private CanonicalLocationIdentityService locationIdentityService;
    @Autowired(required = false)
    private PollutionSourceAttributionEngine engine;
    @Autowired(required = false)
    private GeospatialSourceEvidenceService geospatialEvidenceService;
    @Autowired(required = false)
    private FireHotspotEvidenceService fireHotspotEvidenceService;
    @Autowired(required = false)
    private SatelliteEvidenceService satelliteEvidenceService;

    private final Map<String, AttributionResult> attributionCache = new ConcurrentHashMap<>();

    public PollutionAttributionService() {
    }

    public PollutionAttributionService(DataFusionService dataFusionService) {
        this.dataFusionService = dataFusionService;
    }

    public AttributionResult attribute(AttributionRequest request) {
        AttributionRequest normalized = (request != null ? request : AttributionRequest.builder().build()).normalized();
        CityEnvironmentalContext context = dataFusionService != null
                ? dataFusionService.buildContext(FusionRequest.builder()
                .cityId(normalized.getCityId())
                .placeId(normalized.getPlaceId())
                .cityName(normalized.getCityName())
                .state(normalized.getState())
                .country(normalized.getCountry())
                .latitude(normalized.getLatitude())
                .longitude(normalized.getLongitude())
                .parameters(Map.of("refresh", normalized.isRefreshEvidence()))
                .build())
                : CityEnvironmentalContext.empty(normalized.getCityId());
        return attribute(context, normalized);
    }

    public AttributionResult attribute(CityEnvironmentalContext context, AttributionRequest request) {
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(null);
        AttributionRequest safeRequest = (request != null ? request : AttributionRequest.builder().build()).normalized();
        Point center = center(safeContext, safeRequest);
        String country = valueOrDefault(safeRequest.getCountry(), "India");
        CanonicalLocationIdentity identity = identity(safeRequest, safeContext, center, country);
        String locationHash = SnapshotIdentity.locationHash(center.lat(), center.lon());
        String snapshotId = snapshotId(safeContext, center);
        String cacheKey = cacheKey(identity.locationKey(), snapshotId);
        AttributionResult cached = safeRequest.isRefreshEvidence() ? null : attributionCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        PollutionAttributionInput input = input(safeContext, safeRequest, center, identity, snapshotId, locationHash);
        AttributionResult result = engine().calculate(input);
        if (attributionCache.size() >= CACHE_MAX_ENTRIES) attributionCache.clear();
        attributionCache.put(cacheKey, result);
        return result;
    }

    private PollutionAttributionInput input(CityEnvironmentalContext context, AttributionRequest request, Point center,
                                            CanonicalLocationIdentity identity, String snapshotId, String locationHash) {
        PollutantProfile pollutants = readPollutants(context);
        Map<String, Object> weather = weather(context);
        Map<String, Object> geospatial = geospatial(identity.locationKey(), center);
        Map<String, Object> fire = fire(identity.locationKey(), center, number(weather.get("windDirectionDegrees")), number(weather.get("windSpeedMps")));
        Map<String, Object> satellite = satellite().normalize(context.getSatellite());
        Map<String, Object> currentAqi = currentAqi(context);
        Instant generatedAt = context.getTimestamp() != null ? context.getTimestamp() : Instant.now();
        return PollutionAttributionInput.builder()
                .location(location(identity, request, context, center))
                .currentAqi(currentAqi)
                .pollutants(pollutants.toMap())
                .weather(weather)
                .temporal(temporal(generatedAt))
                .geospatial(geospatial)
                .fireEvidence(fire)
                .satelliteEvidence(satellite)
                .historicalContext(historicalContext(context))
                .providerStatus(context.getProviderStatus() != null ? new LinkedHashMap<>(context.getProviderStatus()) : Map.of())
                .snapshotId(snapshotId)
                .locationKey(identity.locationKey())
                .snapshotObservedAt(metadata(context, "snapshotObservedAt"))
                .snapshotGeneratedAt(metadata(context, "snapshotGeneratedAt"))
                .snapshotReused(Boolean.TRUE.equals(metadata(context, "snapshotReused")))
                .locationHash(locationHash)
                .generatedAt(generatedAt)
                .build();
    }

    public Map<String, Object> debug(AttributionRequest request) {
        AttributionRequest normalized = (request != null ? request : AttributionRequest.builder().build()).normalized();
        CityEnvironmentalContext context = dataFusionService != null
                ? dataFusionService.buildContext(FusionRequest.builder()
                .cityId(normalized.getCityId())
                .placeId(normalized.getPlaceId())
                .cityName(normalized.getCityName())
                .state(normalized.getState())
                .country(normalized.getCountry())
                .latitude(normalized.getLatitude())
                .longitude(normalized.getLongitude())
                .parameters(Map.of("refresh", normalized.isRefreshEvidence()))
                .build())
                : CityEnvironmentalContext.empty(normalized.getCityId());
        AttributionResult result = attribute(context, normalized);
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("canonicalLocationIdentity", result.getLocation());
        debug.put("selectedCurrentAqi", result.getCurrentAqi());
        debug.put("pollutantValuesUsed", result.getDiagnostics().get("pollutantsUsed"));
        debug.put("weatherValuesUsed", result.getDiagnostics().get("weatherUsed"));
        debug.put("historicalValuesUsed", result.getDiagnostics().get("historicalContext"));
        debug.put("geospatialEvidence", result.getDiagnostics().get("geospatialEvidence"));
        debug.put("fireEvidence", result.getDiagnostics().get("fireEvidence"));
        debug.put("satelliteEvidence", result.getDiagnostics().get("satelliteEvidence"));
        debug.put("sourceSpecificRawScores", result.getDiagnostics().get("sourceSpecificRawScores"));
        debug.put("sourceDebugDetails", result.getDiagnostics().get("sourceDebugDetails"));
        debug.put("positiveEvidence", result.getDiagnostics().get("positiveEvidence"));
        debug.put("contradictingEvidence", result.getDiagnostics().get("contradictingEvidence"));
        debug.put("missingEvidence", result.getDiagnostics().get("missingEvidence"));
        debug.put("normalizationAfterRounding", result.getDiagnostics().get("normalizationAfterRounding"));
        debug.put("unknownContributionCalculation", result.getDiagnostics().get("unknownContributionCalculation"));
        debug.put("confidenceCalculations", result.getSources().stream().collect(java.util.stream.Collectors.toMap(
                item -> item.getSourceType().name(),
                item -> Map.of("confidence", item.getConfidence(), "label", item.getConfidenceLabel(),
                        "positiveEvidenceCount", item.getPositiveEvidenceCount(),
                        "contradictingEvidenceCount", item.getContradictingEvidenceCount()),
                (left, right) -> left,
                LinkedHashMap::new
        )));
        debug.put("finalWarnings", result.getWarnings());
        debug.put("result", result);
        return debug;
    }

    private Map<String, Object> location(CanonicalLocationIdentity identity, AttributionRequest request,
                                         CityEnvironmentalContext context, Point center) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("locationKey", identity.locationKey());
        map.put("keyVersion", identity.keyVersion());
        map.put("displayName", displayName(request, context));
        map.put("city", valueOrDefault(request.getCityName(), valueOrDefault(context.getCity(), request.getCityId())));
        map.put("state", request.getState());
        map.put("country", valueOrDefault(request.getCountry(), "India"));
        map.put("latitude", center.lat());
        map.put("longitude", center.lon());
        map.put("roundedLatitude", identity.roundedLatitude());
        map.put("roundedLongitude", identity.roundedLongitude());
        return map;
    }

    private String displayName(AttributionRequest request, CityEnvironmentalContext context) {
        return java.util.stream.Stream.of(
                        valueOrDefault(request.getCityName(), valueOrDefault(context.getCity(), request.getCityId())),
                        request.getState(),
                        valueOrDefault(request.getCountry(), "India"))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private Map<String, Object> currentAqi(CityEnvironmentalContext context) {
        Map<String, Object> aqi = safeMap(context.getAqi());
        Map<String, Object> selected = safeMap(aqi.get("selected"));
        Map<String, Object> source = !selected.isEmpty() ? selected : aqi;
        Map<String, Object> result = new LinkedHashMap<>();
        putIfPresent(result, "value", first(source, "currentAqi", "aqi", "aqius"));
        putIfPresent(result, "standard", first(source, "standard", "aqiStandard"));
        putIfPresent(result, "provider", first(source, "provider", "source"));
        putIfPresent(result, "primaryPollutant", first(source, "primaryPollutant"));
        putIfPresent(result, "observedAt", first(source, "observedAt", "timestamp", "lastUpdated"));
        putIfPresent(result, "freshnessStatus", first(source, "freshnessStatus"));
        return result;
    }

    private Map<String, Object> weather(CityEnvironmentalContext context) {
        Map<String, Object> raw = safeMap(context.getWeather());
        Map<String, Object> wind = safeMap(context.getWind());
        Map<String, Object> result = new LinkedHashMap<>();
        putNullable(result, "temperatureCelsius", firstPresent(context.getTemperature(), raw.get("temperature"), raw.get("temperatureCelsius")));
        putNullable(result, "humidityPercent", firstPresent(context.getHumidity(), raw.get("humidity"), raw.get("humidityPercent")));
        putNullable(result, "windSpeedMps", firstPresent(wind.get("speed"), raw.get("windSpeed"), raw.get("windSpeedMps")));
        putNullable(result, "windDirectionDegrees", firstPresent(wind.get("direction"), raw.get("windDirection"), raw.get("windDirectionDegrees")));
        putNullable(result, "rainfallMm", firstPresent(raw.get("rainfall"), raw.get("rainfallMm"), raw.get("rainProbability")));
        putNullable(result, "pressureHpa", firstPresent(raw.get("pressure"), raw.get("pressureHpa")));
        putIfPresent(result, "observedAt", first(raw, "timestamp", "lastUpdated", "responseTimestamp"));
        return result;
    }

    private Map<String, Object> temporal(Instant instant) {
        ZonedDateTime local = instant.atZone(ZoneId.of("Asia/Kolkata"));
        int hour = local.getHour();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("localHour", hour);
        map.put("dayOfWeek", local.getDayOfWeek().name());
        map.put("month", local.getMonthValue());
        map.put("season", season(local.getMonthValue()));
        map.put("isRushHour", (hour >= 8 && hour <= 11) || (hour >= 17 && hour <= 20));
        map.put("isWeekend", local.getDayOfWeek().getValue() >= 6);
        return map;
    }

    private Map<String, Object> historicalContext(CityEnvironmentalContext context) {
        List<Map<String, Object>> history = context.getHistoricalAQI() != null ? context.getHistoricalAQI() : List.of();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", history.size() >= 3);
        map.put("observationCount", history.size());
        map.put("calculationMethod", history.size() >= 3 ? "recent same-context observations from fused snapshot" : "insufficient history");
        map.put("timeWindow", "fused_context");
        map.put("meanAqi", history.stream().mapToDouble(item -> number(item.get("aqi"))).filter(value -> value > 0).average().stream().boxed().findFirst().orElse(null));
        return map;
    }

    private Map<String, Object> geospatial(String locationKey, Point center) {
        if (geospatialEvidenceService != null) return geospatialEvidenceService.collect(locationKey, center.lat(), center.lon());
        return Map.of("available", false, "provider", "OPENSTREETMAP_OVERPASS", "dataOrigin", "UNAVAILABLE", "warnings", List.of("GEOSPATIAL_SERVICE_UNAVAILABLE"));
    }

    private Map<String, Object> fire(String locationKey, Point center, double windDirection, double windSpeed) {
        if (fireHotspotEvidenceService != null) return fireHotspotEvidenceService.collect(locationKey, center.lat(), center.lon(), windDirection, windSpeed);
        return Map.of("available", false, "provider", "NASA_FIRMS", "dataOrigin", "UNAVAILABLE", "warnings", List.of("FIRE_PROVIDER_UNAVAILABLE"));
    }

    private SatelliteEvidenceService satellite() {
        return satelliteEvidenceService != null ? satelliteEvidenceService : new SatelliteEvidenceService();
    }

    private PollutionSourceAttributionEngine engine() {
        return engine != null ? engine : new PollutionSourceAttributionEngine(new SourceAttributionProperties());
    }

    private CanonicalLocationIdentity identity(AttributionRequest request, CityEnvironmentalContext context, Point center, String country) {
        String city = valueOrDefault(request.getCityName(), valueOrDefault(context.getCity(), request.getCityId()));
        if (locationIdentityService != null) {
            return locationIdentityService.identity(city, request.getState(), country, center.lat(), center.lon());
        }
        String key = "in:" + SnapshotIdentity.normalizedLatitude(center.lat()) + ":" + SnapshotIdentity.normalizedLongitude(center.lon());
        return new CanonicalLocationIdentity(key, "v2", "in", "india", slug(request.getState()), slug(city),
                center.lat(), center.lon(), center.lat(), center.lon(), List.of());
    }

    private Point center(CityEnvironmentalContext context, AttributionRequest request) {
        Optional<Point> contextPoint = coordinate(context.getCoordinates());
        if (contextPoint.isPresent()) return contextPoint.get();
        if (request.getLatitude() != null && request.getLongitude() != null) return new Point(request.getLatitude(), request.getLongitude());
        return new Point(0.0, 0.0);
    }

    private PollutantProfile readPollutants(CityEnvironmentalContext context) {
        PollutantAccumulator accumulator = new PollutantAccumulator();
        Map<String, Object> aqi = safeMap(context.getAqi());
        accumulator.addDirect(aqi);
        accumulator.addDirect(safeMap(aqi.get("selected")));
        Object stations = aqi.get("stations");
        if (stations instanceof List<?> stationList) {
            for (Object station : stationList) {
                Map<String, Object> stationMap = safeMap(station);
                Object rows = stationMap.get("pollutants");
                if (rows instanceof List<?> rowList) accumulator.addRows(rowList);
                else accumulator.addDirect(safeMap(rows));
            }
        }
        return accumulator.toProfile();
    }

    private String snapshotId(CityEnvironmentalContext context, Point center) {
        Object contextSnapshotId = context.getMetadata() != null ? context.getMetadata().get("snapshotId") : null;
        if (contextSnapshotId != null && !String.valueOf(contextSnapshotId).isBlank()) return String.valueOf(contextSnapshotId);
        String raw = center.lat() + "|" + center.lon() + "|" + context.getProviderStatus() + "|" + providerTimestamps(context);
        return "snap-" + sha256(raw).substring(0, 16);
    }

    private Map<String, Object> providerTimestamps(CityEnvironmentalContext context) {
        Map<String, Object> timestamps = new LinkedHashMap<>();
        putIfPresent(timestamps, "aqi", first(context.getAqi(), "timestamp", "observedAt", "lastUpdated", "fetchedAt"));
        putIfPresent(timestamps, "weather", first(context.getWeather(), "timestamp", "lastUpdated", "responseTimestamp"));
        putIfPresent(timestamps, "satellite", first(context.getSatellite(), "timestamp", "lastUpdated"));
        return timestamps;
    }

    private String cacheKey(String locationKey, String snapshotId) {
        return locationKey + ":" + snapshotId;
    }

    private Optional<Point> coordinate(Map<String, Object> coordinates) {
        double lat = firstPositive(number(coordinates.get("latitude")), number(coordinates.get("lat")));
        double lon = firstPositive(number(coordinates.get("longitude")), number(coordinates.get("lng")), number(coordinates.get("lon")));
        return lat != 0.0 && lon != 0.0 ? Optional.of(new Point(lat, lon)) : Optional.empty();
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) map.put(key, value);
    }

    private void putNullable(Map<String, Object> map, String key, Object value) {
        map.put(key, value instanceof Number number && number.doubleValue() == 0.0 ? null : value);
    }

    private Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) if (map.get(key) != null && !String.valueOf(map.get(key)).isBlank()) return map.get(key);
        return null;
    }

    private Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private Object metadata(CityEnvironmentalContext context, String key) {
        return context != null && context.getMetadata() != null ? context.getMetadata().get(key) : null;
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : (fallback != null ? fallback : "");
    }

    private double firstPositive(double... values) {
        for (double value : values) if (value > 0) return value;
        return 0.0;
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String season(int month) {
        if (month >= 6 && month <= 9) return "MONSOON";
        if (month >= 10 && month <= 11) return "POST_MONSOON";
        if (month == 12 || month <= 2) return "WINTER";
        return "SUMMER";
    }

    private String slug(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private record Point(double lat, double lon) {}

    private class PollutantAccumulator {
        private Double aqi;
        private Double pm25;
        private Double pm10;
        private Double no2;
        private Double so2;
        private Double co;
        private Double o3;
        private Double nh3;

        private void addDirect(Map<String, Object> values) {
            if (values == null || values.isEmpty()) return;
            aqi = firstNumber(aqi, first(values, "aqi", "currentAqi", "value"));
            pm25 = firstNumber(pm25, first(values, "pm25", "pm2_5", "pm2.5"));
            pm10 = firstNumber(pm10, values.get("pm10"));
            no2 = firstNumber(no2, values.get("no2"));
            so2 = firstNumber(so2, values.get("so2"));
            co = firstNumber(co, values.get("co"));
            o3 = firstNumber(o3, values.get("o3"));
            nh3 = firstNumber(nh3, values.get("nh3"));
        }

        private void addRows(List<?> rows) {
            for (Object row : rows) {
                Map<String, Object> map = safeMap(row);
                String pollutant = String.valueOf(map.getOrDefault("normalizedPollutant", map.getOrDefault("pollutant", ""))).toLowerCase(Locale.ROOT).replace(".", "");
                Object value = first(map, "concentration", "value", "subIndex", "avg_value");
                switch (pollutant) {
                    case "pm25" -> pm25 = firstNumber(pm25, value);
                    case "pm10" -> pm10 = firstNumber(pm10, value);
                    case "no2" -> no2 = firstNumber(no2, value);
                    case "so2" -> so2 = firstNumber(so2, value);
                    case "co" -> co = firstNumber(co, value);
                    case "o3" -> o3 = firstNumber(o3, value);
                    case "nh3" -> nh3 = firstNumber(nh3, value);
                    default -> { }
                }
            }
        }

        private Double firstNumber(Double current, Object candidate) {
            if (current != null) return current;
            double parsed = number(candidate);
            return parsed > 0 ? parsed : null;
        }

        private PollutantProfile toProfile() {
            return new PollutantProfile(aqi, pm25, pm10, no2, so2, co, o3, nh3);
        }
    }

    private record PollutantProfile(Double aqi, Double pm25, Double pm10, Double no2, Double so2, Double co, Double o3, Double nh3) {
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("aqi", aqi);
            map.put("pm25", pm25);
            map.put("pm10", pm10);
            map.put("no2", no2);
            map.put("so2", so2);
            map.put("co", co);
            map.put("o3", o3);
            map.put("nh3", nh3);
            return map;
        }
    }
}
