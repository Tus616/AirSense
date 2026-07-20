package com.airsense.api.fusion;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CpcbAqiService {
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private final RestTemplateBuilder restTemplateBuilder;
    private final AqiHistoricalSnapshotRepository historicalSnapshotRepository;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${cpcb.data-gov.api-key:${CPCB_DATA_GOV_API_KEY:${CPCB_DATA_GOV_IN_API_KEY:${DATA_GOV_IN_API_KEY:}}}}")
    private String apiKey;

    @Value("${cpcb.resource-id:${CPCB_RESOURCE_ID:${DATA_GOV_IN_CPCB_RESOURCE_ID:3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69}}}")
    private String resourceId;

    @Value("${cpcb.base-url:${DATA_GOV_IN_BASE_URL:https://api.data.gov.in/resource}}")
    private String baseUrl;

    @Value("${cpcb.freshness-minutes:${CPCB_STALE_AFTER_MINUTES:${CPCB_FRESHNESS_MINUTES:180}}}")
    private long freshnessMinutes;

    @Value("${cpcb.cache-ttl-seconds:${CPCB_CACHE_TTL_SECONDS:300}}")
    private long cacheTtlSeconds;

    @Value("${cpcb.request-timeout-seconds:${CPCB_REQUEST_TIMEOUT_SECONDS:10}}")
    private long requestTimeoutSeconds;

    public Map<String, Object> fetchCanonicalAqi(FusionRequest request, double latitude, double longitude) {
        Map<String, Object> unavailable = unavailable("CPCB unavailable");
        String lookupCity = lookupCity(request);
        if (!isIndia(request) || lookupCity.isBlank()) {
            unavailable.put("reason", "CPCB lookup applies only to Indian locations with city metadata");
            return unavailable;
        }
        if (apiKey == null || apiKey.isBlank()) {
            unavailable.put("reason", "CPCB_DATA_GOV_API_KEY is not configured");
            return unavailable;
        }

        String locationHash = SnapshotIdentity.locationHash(latitude, longitude);
        String cacheKey = String.join("|",
                lookupCity.toLowerCase(Locale.ROOT),
                text(request.getState()).toLowerCase(Locale.ROOT),
                SnapshotIdentity.normalizedLatitude(latitude),
                SnapshotIdentity.normalizedLongitude(longitude));
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
            Map<String, Object> cachedData = new LinkedHashMap<>(cached.data());
            cachedData.put("cacheStatus", "HIT");
            log.info("CPCB AQI using cached provider=CPCB_CAAQMS city={} state={} lat={} lng={}",
                    lookupCity, request.getState(), latitude, longitude);
            return cachedData;
        }

        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .setReadTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .build();

        try {
            List<Map<String, Object>> records = new ArrayList<>();
            List<Map<String, Object>> stations = new ArrayList<>();
            List<Map<String, Object>> attemptedLookups = new ArrayList<>();
            Integer httpStatus = null;
            for (String cityVariant : cityVariants(lookupCity)) {
                for (String stateVariant : stateVariants(request.getState(), cityVariant)) {
                    String url = cpcbUrl(cityVariant, stateVariant);
                    log.info("CPCB AQI fetching provider=CPCB_CAAQMS city={} state={} lat={} lng={}",
                            cityVariant, stateVariant, latitude, longitude);
                    ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                    httpStatus = response.getStatusCode().value();
                    List<Map<String, Object>> lookupRecords = records(response.getBody() != null ? response.getBody() : Map.of());
                    List<Map<String, Object>> lookupStations = stationSummaries(lookupRecords, latitude, longitude);
                    lookupStations.forEach(station -> {
                        station.put("lookupCity", cityVariant);
                        station.put("lookupState", stateVariant != null ? stateVariant : "");
                    });
                    attemptedLookups.add(Map.of(
                            "city", cityVariant,
                            "state", stateVariant != null ? stateVariant : "",
                            "recordCount", lookupRecords.size(),
                            "stationCount", lookupStations.size()
                    ));
                    records.addAll(lookupRecords);
                    stations.addAll(lookupStations);
                    if (lookupStations.stream().anyMatch(station -> "LIVE".equals(station.get("freshnessStatus")) && station.get("aqi") instanceof Number)) {
                        break;
                    }
                }
                if (stations.stream().anyMatch(station -> "LIVE".equals(station.get("freshnessStatus")) && station.get("aqi") instanceof Number)) {
                    break;
                }
            }
            Optional<Map<String, Object>> fresh = stations.stream()
                    .filter(station -> "LIVE".equals(station.get("freshnessStatus")))
                    .filter(station -> station.get("aqi") instanceof Number)
                    .filter(station -> station.get("distanceKm") instanceof Number)
                    .min(Comparator.comparingDouble(station -> ((Number) station.get("distanceKm")).doubleValue()));
            Optional<Map<String, Object>> stale = stations.stream()
                    .filter(station -> "STALE".equals(station.get("freshnessStatus")))
                    .filter(station -> station.get("aqi") instanceof Number)
                    .filter(station -> station.get("distanceKm") instanceof Number)
                    .min(Comparator.comparingDouble(station -> ((Number) station.get("distanceKm")).doubleValue()));

            if (fresh.isEmpty()) {
                Optional<Map<String, Object>> local = localStationFallback(latitude, longitude, locationHash);
                if (local.isPresent()) {
                    Map<String, Object> result = local.get();
                    result.put("rawRecords", records);
                    result.put("stations", stations);
                    result.put("attemptedLookups", attemptedLookups);
                    result.put("httpStatus", httpStatus);
                    result.put("cacheStatus", "LOCAL_HISTORY_FALLBACK");
                    cache.put(cacheKey, new CacheEntry(new LinkedHashMap<>(result), Instant.now()));
                    return result;
                }
                unavailable.put("reason", stale.isPresent()
                        ? "Fresh CPCB station AQI unavailable; nearest station data is stale."
                        : "Fresh CPCB station AQI unavailable for selected city/state/coordinates.");
                unavailable.put("snapshotId", SnapshotIdentity.snapshotId(latitude, longitude, Map.of("cpcb", Instant.now().toString()), Map.of("aqi", "UNAVAILABLE")));
                unavailable.put("locationHash", locationHash);
                unavailable.put("rawRecords", records);
                unavailable.put("stations", stations);
                unavailable.put("attemptedLookups", attemptedLookups);
                stale.ifPresent(station -> unavailable.put("nearestStaleStation", stationSummary(station)));
                return unavailable;
            }

            Map<String, Object> station = fresh.get();
            int aqi = ((Number) station.get("aqi")).intValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("aqi", aqi);
        result.put("aqiStandard", "CPCB_INDIAN_NAQI_0_500");
        result.put("aqiCategory", category(aqi));
        result.put("sourceType", station.getOrDefault("sourceType", "STATION_CALCULATED_FROM_CONCENTRATION"));
        result.put("sourceLabel", "CPCB station-based calculated Indian AQI");
        result.put("provider", "CPCB_CAAQMS");
            result.put("stationName", station.get("station"));
            result.put("stationLatitude", station.get("stationLatitude"));
            result.put("stationLongitude", station.get("stationLongitude"));
            result.put("distanceKm", station.get("distanceKm"));
            result.put("observedAt", station.get("observedAt"));
            result.put("fetchedAt", Instant.now().toString());
            result.put("snapshotId", SnapshotIdentity.snapshotId(latitude, longitude, Map.of("cpcb", station.get("observedAt")), Map.of("aqi", "SUCCESS")));
            result.put("locationHash", locationHash);
            result.put("freshnessStatus", station.get("freshnessStatus"));
            result.put("prominentPollutant", station.get("prominentPollutant"));
        result.put("pollutants", station.get("pollutants"));
        result.put("calculation", station.get("calculation"));
            result.put("rawRecords", records);
            result.put("stations", stations);
            result.put("attemptedLookups", attemptedLookups);
            result.put("httpStatus", httpStatus);
            result.put("cacheStatus", "MISS");
            cache.put(cacheKey, new CacheEntry(new LinkedHashMap<>(result), Instant.now()));
            log.info("CPCB AQI success provider=CPCB_CAAQMS station={} aqi={} freshness={} distanceKm={} httpStatus={}",
                    station.get("station"), aqi, station.get("freshnessStatus"), station.get("distanceKm"), httpStatus);
            return result;
        } catch (RestClientException e) {
            log.warn("CPCB AQI failed provider=CPCB_CAAQMS city={} state={} reason={}", lookupCity, request.getState(), e.getMessage());
            Optional<Map<String, Object>> local = localStationFallback(latitude, longitude, locationHash);
            if (local.isPresent()) {
                Map<String, Object> result = local.get();
                result.put("providerLookupReason", e.getMessage());
                result.put("cacheStatus", "LOCAL_HISTORY_FALLBACK");
                cache.put(cacheKey, new CacheEntry(new LinkedHashMap<>(result), Instant.now()));
                return result;
            }
            unavailable.put("reason", e.getMessage());
            return unavailable;
        }
    }

    private Optional<Map<String, Object>> localStationFallback(double latitude, double longitude, String locationHash) {
        if (historicalSnapshotRepository == null) {
            return Optional.empty();
        }
        Instant earliest = Instant.now().minus(Duration.ofMinutes(freshnessMinutes));
        return historicalSnapshotRepository
                .findTop1000ByProviderAndAqiStandardAndProviderObservedAtAfterOrderByProviderObservedAtDesc(
                        "CPCB_CAAQMS", "INDIA_NAQI", earliest)
                .stream()
                .filter(snapshot -> snapshot.getCurrentAqi() != null && snapshot.getCurrentAqi() > 0)
                .filter(snapshot -> snapshot.getStationLatitude() != null && snapshot.getStationLongitude() != null)
                .filter(snapshot -> pollutantRows(snapshot).stream().filter(row -> Boolean.TRUE.equals(row.get("valid"))).count() >= 3)
                .min(Comparator.comparingDouble(snapshot -> distanceKm(latitude, longitude,
                        snapshot.getStationLatitude(), snapshot.getStationLongitude())))
                .filter(snapshot -> distanceKm(latitude, longitude, snapshot.getStationLatitude(), snapshot.getStationLongitude()) <= 50.0)
                .map(snapshot -> localResult(snapshot, latitude, longitude, locationHash));
    }

    private Map<String, Object> localResult(AqiHistoricalSnapshot snapshot, double latitude, double longitude, String locationHash) {
        double distance = round(distanceKm(latitude, longitude, snapshot.getStationLatitude(), snapshot.getStationLongitude()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("aqi", snapshot.getCurrentAqi());
        result.put("aqiStandard", "CPCB_INDIAN_NAQI_0_500");
        result.put("aqiCategory", category(snapshot.getCurrentAqi()));
        result.put("sourceType", "LOCAL_ALL_STATION_HISTORY");
        result.put("sourceLabel", "CPCB all-station hourly snapshot");
        result.put("provider", "CPCB_CAAQMS");
        result.put("stationName", snapshot.getStationName());
        result.put("stationLatitude", snapshot.getStationLatitude());
        result.put("stationLongitude", snapshot.getStationLongitude());
        result.put("distanceKm", distance);
        result.put("observedAt", snapshot.getProviderObservedAt() != null ? snapshot.getProviderObservedAt().toString() : null);
        result.put("fetchedAt", Instant.now().toString());
        result.put("snapshotId", SnapshotIdentity.snapshotId(latitude, longitude,
                Map.of("cpcb", snapshot.getProviderObservedAt() != null ? snapshot.getProviderObservedAt().toString() : Instant.now().toString()),
                Map.of("aqi", "LOCAL_HISTORY_FALLBACK")));
        result.put("locationHash", locationHash);
        result.put("freshnessStatus", snapshot.getProviderObservedAt() != null ? freshnessStatus(snapshot.getProviderObservedAt()) : "UNAVAILABLE");
        result.put("prominentPollutant", snapshot.getPrimaryPollutant());
        result.put("pollutants", pollutantRows(snapshot));
        result.put("calculation", Map.of("method", "stored_cpcb_all_station_snapshot"));
        result.put("stations", List.of(Map.of(
                "station", snapshot.getStationName(),
                "stationLatitude", snapshot.getStationLatitude(),
                "stationLongitude", snapshot.getStationLongitude(),
                "distanceKm", distance,
                "aqi", snapshot.getCurrentAqi(),
                "observedAt", snapshot.getProviderObservedAt() != null ? snapshot.getProviderObservedAt().toString() : "",
                "freshnessStatus", snapshot.getProviderObservedAt() != null ? freshnessStatus(snapshot.getProviderObservedAt()) : "UNAVAILABLE"
        )));
        log.info("CPCB AQI local fallback provider=CPCB_CAAQMS station={} aqi={} distanceKm={}",
                snapshot.getStationName(), snapshot.getCurrentAqi(), distance);
        return result;
    }

    private List<Map<String, Object>> pollutantRows(AqiHistoricalSnapshot snapshot) {
        List<Map<String, Object>> rows = new ArrayList<>();
        addPollutant(rows, "PM2.5", "PM25", snapshot.getPm25());
        addPollutant(rows, "PM10", "PM10", snapshot.getPm10());
        addPollutant(rows, "NO2", "NO2", snapshot.getNo2());
        addPollutant(rows, "SO2", "SO2", snapshot.getSo2());
        addPollutant(rows, "CO", "CO", snapshot.getCo());
        addPollutant(rows, "O3", "O3", snapshot.getO3());
        addPollutant(rows, "NH3", "NH3", snapshot.getNh3());
        return rows;
    }

    private void addPollutant(List<Map<String, Object>> rows, String pollutant, String normalized, Double value) {
        if (value == null || value < 0) {
            return;
        }
        rows.add(Map.of(
                "pollutant", pollutant,
                "normalizedPollutant", normalized,
                "avg", value,
                "concentration", value,
                "valid", true
        ));
    }

    public List<Map<String, Object>> fetchStationCatalogue(String city, String state, double latitude, double longitude) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .setReadTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .build();
        List<Map<String, Object>> stations = new ArrayList<>();
        for (String cityVariant : cityVariants(city)) {
            for (String stateVariant : stateVariants(state, cityVariant)) {
                try {
                    ResponseEntity<Map> response = restTemplate.getForEntity(cpcbUrl(cityVariant, stateVariant), Map.class);
                    List<Map<String, Object>> lookupRecords = records(response.getBody() != null ? response.getBody() : Map.of());
                    List<Map<String, Object>> lookupStations = stationSummaries(lookupRecords, latitude, longitude);
                    lookupStations.forEach(station -> {
                        station.put("lookupCity", cityVariant);
                        station.put("lookupState", stateVariant != null ? stateVariant : "");
                    });
                    stations.addAll(lookupStations);
                } catch (RestClientException e) {
                    log.warn("CPCB station catalogue failed city={} state={} reason={}", cityVariant, stateVariant, e.getMessage());
                }
            }
        }
        return stations.stream()
                .collect(Collectors.toMap(station -> text(station.get("station")).toLowerCase(Locale.ROOT),
                        station -> station, (left, right) -> left, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    public AllStationFetchResult fetchAllStationObservations() {
        if (apiKey == null || apiKey.isBlank()) {
            return new AllStationFetchResult(0, 0, 0, List.of(), null, 0, List.of("CPCB_DATA_GOV_API_KEY is not configured"));
        }
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .setReadTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .build();
        int limit = 1000;
        int offset = 0;
        Integer total = null;
        List<Map<String, Object>> allRecords = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        while (total == null || offset < total) {
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(cpcbUrlPage(offset, limit), Map.class);
                Map<String, Object> body = response.getBody() != null ? response.getBody() : Map.of();
                if (total == null) {
                    total = integer(body.get("total"));
                }
                List<Map<String, Object>> pageRecords = records(body);
                allRecords.addAll(pageRecords);
                if (pageRecords.isEmpty() || pageRecords.size() < limit) {
                    break;
                }
                offset += limit;
            } catch (RestClientException e) {
                failures.add("offset=" + offset + ":" + e.getMessage());
                break;
            }
        }
        List<Map<String, Object>> stations = stationSummaries(allRecords, 0.0, 0.0).stream()
                .collect(Collectors.toMap(station -> text(station.get("station")).toLowerCase(Locale.ROOT),
                        station -> station, (left, right) -> left, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        Instant providerTimestamp = stations.stream()
                .map(station -> parseTimestamp(text(station.get("observedAt"))))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new AllStationFetchResult(total != null ? total : allRecords.size(), allRecords.size(), stations.size(),
                stations, providerTimestamp, (offset / limit) + 1, failures);
    }

    private String lookupCity(FusionRequest request) {
        String cityName = text(request.getCityName());
        if (!cityName.isBlank()) return cityName;
        String cityId = text(request.getCityId());
        return "UNKNOWN_PLACE".equalsIgnoreCase(cityId) ? "" : cityId;
    }

    private String cpcbUrl(String city, String state) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment(resourceId)
                .queryParam("api-key", apiKey)
                .queryParam("format", "json")
                .queryParam("limit", 1000)
                .queryParam("filters[city]", city)
                .queryParamIfPresent("filters[state]", Optional.ofNullable(blankToNull(state)))
                .build()
                .toUriString();
    }

    private String cpcbUrlPage(int offset, int limit) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment(resourceId)
                .queryParam("api-key", apiKey)
                .queryParam("format", "json")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build()
                .toUriString();
    }

    private List<String> cityVariants(String city) {
        String clean = text(city);
        if (clean.isBlank()) return List.of();
        List<String> variants = new ArrayList<>();
        variants.add(clean);
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.equals("new delhi") || lower.equals("nct of delhi") || lower.equals("delhi ncr")) {
            variants.add("Delhi");
        }
        if (lower.equals("bengaluru")) {
            variants.add("Bangalore");
        }
        if (lower.equals("bangalore")) {
            variants.add("Bengaluru");
        }
        return variants.stream().distinct().toList();
    }

    private List<String> stateVariants(String state, String city) {
        List<String> variants = new ArrayList<>();
        String clean = text(state);
        if (!clean.isBlank()) {
            variants.add(clean);
        }
        String lowerCity = text(city).toLowerCase(Locale.ROOT);
        if (lowerCity.contains("delhi")) {
            variants.add("Delhi");
        }
        variants.add(null);
        return variants.stream().distinct().toList();
    }

    private boolean isIndia(FusionRequest request) {
        String country = request.getCountry();
        return country == null || country.isBlank()
                || "india".equalsIgnoreCase(country)
                || "in".equalsIgnoreCase(country);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(Map<String, Object> body) {
        Object value = body.get("records");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    private List<Map<String, Object>> stationSummaries(List<Map<String, Object>> records, double requestLat, double requestLng) {
        Map<String, List<Map<String, Object>>> grouped = records.stream()
                .collect(Collectors.groupingBy(record -> text(first(record, "station", "station_name", "stationName")),
                        LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> stations = new ArrayList<>();
        grouped.forEach((stationName, stationRecords) -> buildStation(stationName, stationRecords, requestLat, requestLng).ifPresent(stations::add));
        return stations;
    }

    private Optional<Map<String, Object>> buildStation(String stationName, List<Map<String, Object>> stationRecords, double requestLat, double requestLng) {
        Map<String, Object> first = stationRecords.get(0);
        Double lat = number(first(first, "latitude", "lat", "station_latitude"));
        Double lng = number(first(first, "longitude", "lng", "lon", "station_longitude"));
        Instant observedAt = stationRecords.stream()
                .map(record -> parseTimestamp(text(first(record, "last_update", "lastUpdate", "last_updated"))))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .max(Comparator.naturalOrder())
                .orElse(null);
        List<PollutantIndex> indexed = stationRecords.stream()
                .map(this::pollutantIndex)
                .filter(index -> index != null)
                .toList();
        List<Map<String, Object>> pollutants = indexed.stream()
                .map(PollutantIndex::asMap)
                .toList();
        OptionalIntWithPollutant stationAqi = stationAqi(stationRecords, indexed);
        if (lat == null || lng == null || observedAt == null || stationAqi.aqi == null) {
            return Optional.empty();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("station", stationName);
        result.put("city", text(first(first, "city")));
        result.put("state", text(first(first, "state")));
        result.put("stationLatitude", lat);
        result.put("stationLongitude", lng);
        result.put("distanceKm", round(distanceKm(requestLat, requestLng, lat, lng)));
        result.put("aqi", stationAqi.aqi);
        result.put("sourceType", stationAqi.sourceType);
        result.put("prominentPollutant", stationAqi.pollutant);
        result.put("pollutants", pollutants);
        result.put("calculation", stationAqi.calculation);
        result.put("observedAt", observedAt.toString());
        result.put("freshnessStatus", freshnessStatus(observedAt));
        return Optional.of(result);
    }

    private PollutantIndex pollutantIndex(Map<String, Object> record) {
        String pollutant = text(first(record, "pollutant_id", "pollutant", "pollutant_name"));
        if (pollutant.isBlank()) return null;
        String normalized = normalizePollutant(pollutant);
        Object rawAvg = first(record, "pollutant_avg", "avg_value", "concentration");
        Double concentration = number(rawAvg);
        String unit = unitFor(record, normalized);
        String unitStatus = unitStatus(record, normalized);
        Double concentrationUsed = concentration != null ? concentrationForIndex(normalized, concentration) : null;
        Breakpoint breakpoint = concentrationUsed != null ? breakpoint(normalized, concentrationUsed) : null;
        Integer subIndex = breakpoint != null ? subIndex(concentrationUsed, breakpoint) : null;
        return new PollutantIndex(
                pollutant,
                normalized,
                rawAvg,
                first(record, "pollutant_min", "min_value"),
                first(record, "pollutant_max", "max_value"),
                unit,
                unitStatus,
                concentration,
                concentrationUsed,
                normalized.equals("CO") && concentration != null && concentration > 10,
                concentration != null && concentration >= 0 && subIndex != null,
                subIndex,
                breakpoint
        );
    }

    private OptionalIntWithPollutant stationAqi(List<Map<String, Object>> records, List<PollutantIndex> pollutants) {
        OptionalIntWithPollutant observed = records.stream()
                .map(record -> first(record, "aqi", "AQI", "air_quality_index"))
                .map(this::integer)
                .filter(Objects::nonNull)
                .findFirst()
                .map(aqi -> new OptionalIntWithPollutant(aqi, "STATION_OBSERVED", "AQI", Map.of(
                        "method", "direct_aqi_field",
                        "label", "CPCB station observed AQI field"
                )))
                .orElse(null);
        if (observed != null) return observed;

        List<PollutantIndex> valid = pollutants.stream()
                .filter(PollutantIndex::valid)
                .toList();
        boolean hasPm = valid.stream().anyMatch(index -> index.normalizedPollutant().equals("PM25") || index.normalizedPollutant().equals("PM10"));
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("method", "max_valid_pollutant_sub_index");
        calculation.put("label", "CPCB station-based calculated Indian AQI");
        calculation.put("validPollutantCount", valid.size());
        calculation.put("requiresAtLeastThreePollutants", true);
        calculation.put("requiresPm25OrPm10", true);
        calculation.put("hasPm25OrPm10", hasPm);
        calculation.put("eligible", valid.size() >= 3 && hasPm);
        calculation.put("pollutantsUsed", valid.stream().map(PollutantIndex::normalizedPollutant).toList());
        if (valid.size() < 3 || !hasPm) {
            calculation.put("reason", "AQI unavailable: CPCB calculation requires at least 3 valid pollutants including PM2.5 or PM10.");
            return new OptionalIntWithPollutant(null, "UNAVAILABLE", "", calculation);
        }

        return valid.stream()
                .max(Comparator.comparingInt(PollutantIndex::subIndex))
                .map(index -> new OptionalIntWithPollutant(index.subIndex(), "STATION_CALCULATED_FROM_CONCENTRATION", index.pollutant(), calculation))
                .orElse(new OptionalIntWithPollutant(null, "UNAVAILABLE", "", calculation));
    }

    private Breakpoint breakpoint(String normalizedPollutant, Double concentration) {
        if (concentration == null || concentration < 0) return null;
        return switch (normalizedPollutant) {
            case "PM25" -> breakpoint(concentration, new double[]{0, 31, 61, 91, 121, 251}, new double[]{30, 60, 90, 120, 250, 500}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            case "PM10" -> breakpoint(concentration, new double[]{0, 51, 101, 251, 351, 431}, new double[]{50, 100, 250, 350, 430, 600}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            case "NO2" -> breakpoint(concentration, new double[]{0, 41, 81, 181, 281, 401}, new double[]{40, 80, 180, 280, 400, 800}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            case "SO2" -> breakpoint(concentration, new double[]{0, 41, 81, 381, 801, 1601}, new double[]{40, 80, 380, 800, 1600, 2400}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            case "O3" -> breakpoint(concentration, new double[]{0, 51, 101, 169, 209, 749}, new double[]{50, 100, 168, 208, 748, 1000}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            case "CO" -> breakpoint(concentration, new double[]{0, 1.1, 2.1, 10.1, 17.1, 34.1}, new double[]{1, 2, 10, 17, 34, 50}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            case "NH3" -> breakpoint(concentration, new double[]{0, 201, 401, 801, 1201, 1801}, new double[]{200, 400, 800, 1200, 1800, 2400}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            case "PB" -> breakpoint(concentration, new double[]{0, 0.51, 1.1, 2.1, 3.1, 3.51}, new double[]{0.5, 1.0, 2.0, 3.0, 3.5, 5.0}, new int[]{0, 51, 101, 201, 301, 401}, new int[]{50, 100, 200, 300, 400, 500});
            default -> null;
        };
    }

    private Breakpoint breakpoint(double concentration, double[] cLow, double[] cHigh, int[] iLow, int[] iHigh) {
        for (int i = 0; i < cLow.length; i++) {
            if (concentration <= cHigh[i]) {
                return new Breakpoint(cLow[i], cHigh[i], iLow[i], iHigh[i]);
            }
        }
        return new Breakpoint(cLow[cLow.length - 1], cHigh[cHigh.length - 1], iLow[iLow.length - 1], iHigh[iHigh.length - 1]);
    }

    private Integer subIndex(double concentration, Breakpoint breakpoint) {
        if (breakpoint == null) return null;
        if (concentration < 0) return null;
        if (breakpoint.concentrationHigh() == breakpoint.concentrationLow()) {
            return breakpoint.indexHigh();
        }
        return (int) Math.round(((breakpoint.indexHigh() - breakpoint.indexLow()) / (breakpoint.concentrationHigh() - breakpoint.concentrationLow()))
                * (concentration - breakpoint.concentrationLow()) + breakpoint.indexLow());
    }

    private Double concentrationForIndex(String normalizedPollutant, Double concentration) {
        if (concentration == null) return 0.0;
        if ("CO".equals(normalizedPollutant) && concentration > 10) {
            return concentration / 100.0;
        }
        return concentration;
    }

    private String normalizePollutant(String pollutant) {
        String p = pollutant.toUpperCase(Locale.ROOT).replace(".", "").replace("_", "").replace(" ", "");
        if (p.contains("PM25")) return "PM25";
        if (p.contains("PM10")) return "PM10";
        if (p.contains("NO2")) return "NO2";
        if (p.contains("SO2")) return "SO2";
        if (p.contains("OZONE") || p.equals("O3")) return "O3";
        if (p.equals("CO") || p.contains("CARBONMONOXIDE")) return "CO";
        if (p.contains("NH3") || p.contains("AMMONIA")) return "NH3";
        if (p.equals("PB") || p.contains("LEAD")) return "PB";
        return p;
    }

    private String unitFor(Map<String, Object> record, String normalizedPollutant) {
        String explicit = text(first(record, "pollutant_unit", "unit", "units"));
        if (!explicit.isBlank()) return explicit;
        return "CO".equals(normalizedPollutant) ? "mg/m3 (implicit CPCB)" : "ug/m3 (implicit CPCB)";
    }

    private String unitStatus(Map<String, Object> record, String normalizedPollutant) {
        String explicit = text(first(record, "pollutant_unit", "unit", "units"));
        if (explicit.isBlank()) return "IMPLICIT_CPCB_DATASET_UNIT";
        String lower = explicit.toLowerCase(Locale.ROOT);
        if ("CO".equals(normalizedPollutant)) {
            return lower.contains("mg") ? "VALID" : "UNEXPECTED_UNIT";
        }
        return lower.contains("ug") || lower.contains("µg") || lower.contains("micro") ? "VALID" : "UNEXPECTED_UNIT";
    }

    private Map<String, Object> breakpointMap(Breakpoint breakpoint) {
        if (breakpoint == null) return Map.of();
        return Map.of(
                "concentrationLow", breakpoint.concentrationLow(),
                "concentrationHigh", breakpoint.concentrationHigh(),
                "indexLow", breakpoint.indexLow(),
                "indexHigh", breakpoint.indexHigh()
        );
    }

    private String statusForValue(Object value, Integer subIndex) {
        String text = text(value);
        if (text.isBlank() || List.of("NA", "N/A", "NULL", "NONE", "-").contains(text.toUpperCase(Locale.ROOT))) {
            return "INVALID";
        }
        return subIndex != null ? "VALID" : "UNSUPPORTED_OR_OUT_OF_RANGE";
    }

    private Map<String, Object> stationSummary(Map<String, Object> station) {
        return station;
    }

    private Map<String, Object> pollutantMap(PollutantIndex index) {
        return index.asMap();
    }

    private Map<String, Object> breakpointFor(PollutantIndex index) {
        return breakpointMap(index.breakpoint());
    }

    private String sourceLabelFor(String sourceType) {
        if ("STATION_CALCULATED_FROM_CONCENTRATION".equals(sourceType)) {
            return "CPCB station-based calculated Indian AQI";
        }
        if ("STATION_OBSERVED".equals(sourceType)) {
            return "CPCB station observed AQI field";
        }
        return null;
    }

    private String category(int aqi) {
        if (aqi <= 50) return "GOOD";
        if (aqi <= 100) return "SATISFACTORY";
        if (aqi <= 200) return "MODERATE";
        if (aqi <= 300) return "POOR";
        if (aqi <= 400) return "VERY_POOR";
        return "SEVERE";
    }

    private String freshnessStatus(Instant observedAt) {
        Duration age = Duration.between(observedAt, Instant.now());
        if (age.isNegative()) return "INVALID";
        return age.toMinutes() <= freshnessMinutes ? "LIVE" : "STALE";
    }

    private Optional<Instant> parseTimestamp(String value) {
        if (value.isBlank()) return Optional.empty();
        for (DateTimeFormatter formatter : TIMESTAMP_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(value, formatter).atZone(INDIA_ZONE).toInstant());
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private Map<String, Object> unavailable(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("provider", "CPCB_CAAQMS");
        result.put("reason", reason);
        result.put("aqiStandard", "CPCB_INDIAN_NAQI_0_500");
        result.put("freshnessStatus", "UNAVAILABLE");
        return result;
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

    private Object first(Map<String, Object> record, String... keys) {
        for (String key : keys) if (record.containsKey(key)) return record.get(key);
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private Integer integer(Object value) {
        Double number = number(value);
        return number != null ? (int) Math.round(number) : null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record AllStationFetchResult(int providerTotalRecords, int recordsReturned, int totalStations,
                                        List<Map<String, Object>> stations, Instant providerTimestamp,
                                        int pageCount, List<String> failedPages) {}

    private record OptionalIntWithPollutant(Integer aqi, String sourceType, String pollutant, Map<String, Object> calculation) {}

    private record Breakpoint(double concentrationLow, double concentrationHigh, int indexLow, int indexHigh) {}

    private record PollutantIndex(String pollutant, String normalizedPollutant, Object rawAvg, Object rawMin, Object rawMax,
                                  String unit, String unitStatus, Double concentration, Double concentrationUsed,
                                  boolean coConversionApplied, boolean valid, Integer subIndex, Breakpoint breakpoint) {
        private Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pollutant", pollutant);
            map.put("normalizedPollutant", normalizedPollutant);
            map.put("avg", rawAvg);
            map.put("min", rawMin);
            map.put("max", rawMax);
            map.put("unit", unit);
            map.put("unitStatus", unitStatus);
            map.put("concentration", concentration);
            map.put("concentrationUsedForIndex", concentrationUsed);
            map.put("coConversionApplied", coConversionApplied);
            map.put("valid", valid);
            map.put("subIndex", subIndex);
            map.put("breakpointUsed", breakpoint != null ? Map.of(
                    "concentrationLow", breakpoint.concentrationLow(),
                    "concentrationHigh", breakpoint.concentrationHigh(),
                    "indexLow", breakpoint.indexLow(),
                    "indexHigh", breakpoint.indexHigh()
            ) : Map.of());
            return map;
        }
    }

    private record CacheEntry(Map<String, Object> data, Instant cachedAt) {
        boolean isExpired(long ttlSeconds) {
            return cachedAt.plusSeconds(ttlSeconds).isBefore(Instant.now());
        }
    }
}
