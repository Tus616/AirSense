package com.airsense.api.history;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.fusion.CpcbAqiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CpcbAllStationCollectionService {
    private final CpcbAqiService cpcbAqiService;
    private final HistoricalAirQualityIngestionService ingestionService;
    private final CanonicalLocationIdentityService locationIdentityService;
    private final HistoricalAqiProperties properties;

    public CollectionRunResult collectAllStations() {
        Instant startedAt = Instant.now();
        CpcbAqiService.AllStationFetchResult fetched = cpcbAqiService.fetchAllStationObservations();
        int valid = 0;
        int inserted = 0;
        int duplicates = 0;
        int stale = 0;
        int failed = fetched.failedPages().size();
        for (Map<String, Object> station : fetched.stations()) {
            try {
                AqiHistoricalSnapshot snapshot = snapshot(station);
                if (snapshot == null) {
                    failed++;
                    continue;
                }
                if (snapshot.getProviderObservedAt() != null
                        && Duration.between(snapshot.getProviderObservedAt(), Instant.now()).toMinutes() > properties.getStaleAfterMinutes()) {
                    stale++;
                    continue;
                }
                valid++;
                HistoricalAirQualityIngestionService.IngestionResult result = ingestionService.saveProviderSnapshot(snapshot);
                if (result.inserted()) {
                    inserted++;
                } else if ("ALREADY_EXISTS".equals(result.status()) || "UPDATED_METADATA".equals(result.status())) {
                    duplicates++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("CPCB all-station snapshot failed station={} reason={}", station.get("station"), e.getMessage());
            }
        }
        CollectionRunResult result = new CollectionRunResult(
                fetched.providerTotalRecords(),
                fetched.totalStations(),
                valid,
                inserted,
                duplicates,
                stale,
                failed,
                fetched.providerTimestamp(),
                Duration.between(startedAt, Instant.now()).toMillis(),
                fetched.pageCount(),
                fetched.failedPages()
        );
        log.info("CPCB all-station collection result={}", result.asMap());
        return result;
    }

    private AqiHistoricalSnapshot snapshot(Map<String, Object> station) {
        Double lat = number(station.get("stationLatitude"));
        Double lon = number(station.get("stationLongitude"));
        Integer aqi = integer(station.get("aqi"));
        Instant observedAt = instant(station.get("observedAt"));
        String stationName = text(station.get("station"));
        if (lat == null || lon == null || aqi == null || observedAt == null || stationName.isBlank()) {
            return null;
        }
        CanonicalLocationIdentity identity = locationIdentityService.identity(null, null, "India", lat, lon);
        Map<String, Double> pollutants = pollutants(station.get("pollutants"));
        return AqiHistoricalSnapshot.builder()
                .locationKey(identity.locationKey())
                .locationKeyVersion(identity.keyVersion())
                .canonicalLocationKey(identity.locationKey())
                .legacyLocationKeys(identity.legacyLocationKeys())
                .searchedDisplayName(stationName)
                .city(text(station.get("city")))
                .state(text(station.get("state")))
                .country("India")
                .latitude(lat)
                .longitude(lon)
                .currentAqi(aqi)
                .aqiStandard("INDIA_NAQI")
                .aqiCategory(category(aqi))
                .primaryPollutant(text(station.get("prominentPollutant")))
                .provider("CPCB_CAAQMS")
                .isFallback(false)
                .stationKey(stationKey(stationName))
                .stationLocationKey(identity.locationKey())
                .stationName(stationName)
                .stationLatitude(lat)
                .stationLongitude(lon)
                .stationDistanceKm(0.0)
                .pm25(pollutants.get("PM25"))
                .pm10(pollutants.get("PM10"))
                .no2(pollutants.get("NO2"))
                .so2(pollutants.get("SO2"))
                .co(pollutants.get("CO"))
                .o3(pollutants.get("O3"))
                .nh3(pollutants.get("NH3"))
                .providerObservedAt(observedAt)
                .ingestedAt(Instant.now())
                .dataOrigin(DataOrigin.LIVE_OPERATIONAL_HISTORY.name())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> pollutants(Object value) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (!(value instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) raw;
            String key = text(row.get("normalizedPollutant"));
            Double concentration = number(row.get("concentration"));
            if (!key.isBlank() && concentration != null) {
                result.put(key, concentration);
            }
        }
        return result;
    }

    private Instant instant(Object value) {
        try {
            return value != null ? Instant.parse(String.valueOf(value)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer integer(Object value) {
        Double number = number(value);
        return number != null ? (int) Math.round(number) : null;
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String stationKey(String stationName) {
        String key = stationName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return key.isBlank() ? null : key;
    }

    private String category(int aqi) {
        if (aqi <= 50) return "GOOD";
        if (aqi <= 100) return "SATISFACTORY";
        if (aqi <= 200) return "MODERATE";
        if (aqi <= 300) return "POOR";
        if (aqi <= 400) return "VERY_POOR";
        return "SEVERE";
    }

    public record CollectionRunResult(int totalStationsReturnedByProvider, int totalStationsDiscovered,
                                      int validObservations, int insertedObservations,
                                      int duplicateObservationsSkipped, int staleObservationsSkipped,
                                      int failedStations, Instant providerTimestamp,
                                      long collectorDurationMillis, int pageCount, List<String> failedPages) {
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalStationsReturnedByProvider", totalStationsReturnedByProvider);
            map.put("totalStationsDiscovered", totalStationsDiscovered);
            map.put("validObservations", validObservations);
            map.put("insertedObservations", insertedObservations);
            map.put("duplicateObservationsSkipped", duplicateObservationsSkipped);
            map.put("staleObservationsSkipped", staleObservationsSkipped);
            map.put("failedStations", failedStations);
            map.put("providerTimestamp", providerTimestamp);
            map.put("collectorDurationMillis", collectorDurationMillis);
            map.put("pageCount", pageCount);
            map.put("failedPages", failedPages);
            return map;
        }
    }
}
