package com.airsense.api.history;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.entities.TrackedAirQualityLocation;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.fusion.DataFusionService;
import com.airsense.api.fusion.FusionRequest;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import com.airsense.api.repositories.TrackedAirQualityLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalAirQualityIngestionService {
    private final DataFusionService dataFusionService;
    private final AqiHistoricalSnapshotRepository snapshotRepository;
    private final TrackedAirQualityLocationRepository trackedLocationRepository;
    private final HistoricalAqiProperties properties;
    private final CanonicalLocationIdentityService locationIdentityService;

    public IngestionResult refresh(LocationRequest request) {
        LocationRequest normalized = request.normalized();
        TrackedLocationOutcome trackedLocationOutcome = registerTrackedLocation(normalized, false);
        CityEnvironmentalContext context = dataFusionService.buildContext(FusionRequest.builder()
                .cityId(valueOrDefault(normalized.city(), "UNKNOWN_PLACE"))
                .cityName(normalized.city())
                .state(normalized.state())
                .country(normalized.country())
                .latitude(normalized.latitude())
                .longitude(normalized.longitude())
                .build());
        return saveFromContext(normalized, context).withTrackedLocationStatus(trackedLocationOutcome.status());
    }

    public IngestionResult saveFromContext(LocationRequest request, CityEnvironmentalContext context) {
        LocationRequest normalized = request.normalized();
        AqiHistoricalSnapshot snapshot = fromContext(normalized, context);
        if (snapshot.getCurrentAqi() == null || snapshot.getProviderObservedAt() == null) {
            snapshot.setDataQualityStatus("PROVIDER_UNAVAILABLE");
            snapshot.setDataOrigin(DataOrigin.UNAVAILABLE.name());
            addWarning(snapshot, "CURRENT_AQI_UNAVAILABLE");
            snapshot.setIngestedAt(Instant.now());
            return new IngestionResult(snapshot, false, List.of("CURRENT_AQI_UNAVAILABLE"), "UNAVAILABLE", "NOT_ATTEMPTED");
        }
        registerStationLocation(snapshot);
        SaveOutcome outcome = saveIdempotently(snapshot);
        updateLastIngested(snapshot.getLocationKey(), outcome.snapshot().getIngestedAt());
        return new IngestionResult(outcome.snapshot(), "INSERTED".equals(outcome.status()), outcome.snapshot().getDataQualityWarnings(), outcome.status(), "NOT_ATTEMPTED");
    }

    public IngestionResult saveProviderSnapshot(AqiHistoricalSnapshot snapshot) {
        if (snapshot == null || snapshot.getCurrentAqi() == null || snapshot.getProviderObservedAt() == null) {
            return new IngestionResult(snapshot, false, List.of("CURRENT_AQI_UNAVAILABLE"), "UNAVAILABLE", "NOT_ATTEMPTED");
        }
        if (snapshot.getIngestedAt() == null) {
            snapshot.setIngestedAt(Instant.now());
        }
        validate(snapshot);
        registerStationLocation(snapshot);
        SaveOutcome outcome = saveIdempotently(snapshot);
        updateLastIngested(outcome.snapshot().getLocationKey(), outcome.snapshot().getIngestedAt());
        return new IngestionResult(outcome.snapshot(), "INSERTED".equals(outcome.status()),
                outcome.snapshot().getDataQualityWarnings(), outcome.status(), "NOT_ATTEMPTED");
    }

    public TrackedLocationOutcome registerTrackedLocation(LocationRequest request, boolean searchOnly) {
        LocationRequest normalized = request.normalized();
        CanonicalLocationIdentity identity = locationIdentityService.identity(
                normalized.city(), normalized.state(), normalized.country(), normalized.latitude(), normalized.longitude());
        String locationKey = identity.locationKey();
        Instant now = Instant.now();
        boolean existed = trackedLocationRepository.findByLocationKey(locationKey).isPresent();
        TrackedAirQualityLocation location = trackedLocationRepository.findByLocationKey(locationKey)
                .orElseGet(() -> trackedLocationRepository.findFirstByLocationKeyIn(identity.legacyLocationKeys()).orElse(null));
        if (location == null) {
            location = TrackedAirQualityLocation.builder()
                        .locationKey(locationKey)
                        .createdAt(now)
                        .trackingEnabled(true)
                        .build();
        }
        applyTrackedLocationMetadata(location, normalized, identity, now);
        try {
            return new TrackedLocationOutcome(trackedLocationRepository.save(location), existed ? "UPDATED" : "INSERTED");
        } catch (DuplicateKeyException e) {
            TrackedAirQualityLocation existing = trackedLocationRepository.findByLocationKey(locationKey)
                    .orElseThrow(() -> e);
            applyTrackedLocationMetadata(existing, normalized, identity, now);
            return new TrackedLocationOutcome(trackedLocationRepository.save(existing), "UPDATED");
        }
    }

    public AqiHistoricalSnapshot fromContext(LocationRequest request, CityEnvironmentalContext context) {
        Map<String, Object> aqi = safeMap(context != null ? context.getAqi() : null);
        Map<String, Object> selected = asMap(aqi.get("selected"));
        Map<String, Object> cpcbEvidence = asMap(aqi.get("cpcbEvidence"));
        Map<String, Object> iqAirEvidence = asMap(aqi.get("iqAirEvidence"));
        Map<String, Object> weather = safeMap(context != null ? context.getWeather() : null);
        Map<String, Object> wind = safeMap(context != null ? context.getWind() : null);
        Map<String, Object> pollutants = asMap(aqi.get("pollutants"));
        if (pollutants.isEmpty()) pollutants = pollutantsFromRows(cpcbEvidence.get("pollutants"));

        String provider = string(first(selected.get("provider"), aqi.get("provider")));
        String standard = string(first(selected.get("standard"), aqi.get("standard")));
        Instant observedAt = instant(first(selected.get("observedAt"), aqi.get("observedAt"), aqi.get("timestamp")));
        Instant weatherObservedAt = instant(first(weather.get("timestamp"), weather.get("lastUpdated")));
        List<String> warnings = new ArrayList<>();

        CanonicalLocationIdentity identity = locationIdentityService.identity(
                request.city(), request.state(), request.country(), request.latitude(), request.longitude());
        Double stationLatitude = number(first(selected.get("stationLatitude"), aqi.get("stationLatitude")));
        Double stationLongitude = number(first(selected.get("stationLongitude"), aqi.get("stationLongitude")));
        String stationName = string(first(selected.get("stationName"), aqi.get("stationName")));
        String stationKey = stationKey(stationName);
        CanonicalLocationIdentity stationIdentity = stationLatitude != null && stationLongitude != null
                ? locationIdentityService.identity(null, null, identity.countryCode(), stationLatitude, stationLongitude)
                : identity;
        boolean cpcb = "CPCB_CAAQMS".equalsIgnoreCase(provider);
        AqiHistoricalSnapshot snapshot = AqiHistoricalSnapshot.builder()
                .locationKey(cpcb ? stationIdentity.locationKey() : identity.locationKey())
                .locationKeyVersion(cpcb ? stationIdentity.keyVersion() : identity.keyVersion())
                .canonicalLocationKey(cpcb ? stationIdentity.locationKey() : identity.locationKey())
                .legacyLocationKeys(cpcb ? stationIdentity.legacyLocationKeys() : identity.legacyLocationKeys())
                .searchedDisplayName(valueOrDefault(request.displayName(), request.city()))
                .city(request.city())
                .state(request.state())
                .country(request.country())
                .latitude(cpcb && stationLatitude != null ? stationLatitude : request.latitude())
                .longitude(cpcb && stationLongitude != null ? stationLongitude : request.longitude())
                .currentAqi(integer(first(selected.get("currentAqi"), aqi.get("currentAqi"))))
                .aqiStandard(standard)
                .aqiCategory(string(first(selected.get("category"), aqi.get("aqiCategory"))))
                .primaryPollutant(string(first(selected.get("primaryPollutant"), aqi.get("primaryPollutant"), aqi.get("prominentPollutant"))))
                .provider(provider)
                .isFallback(Boolean.TRUE.equals(first(selected.get("isFallback"), aqi.get("isFallback"), aqi.get("fallbackUsed"))))
                .stationKey(stationKey)
                .stationLocationKey(stationIdentity.locationKey())
                .stationName(stationName)
                .stationLatitude(stationLatitude)
                .stationLongitude(stationLongitude)
                .stationDistanceKm(number(first(selected.get("distanceKm"), aqi.get("distanceKm"))))
                .providerReturnedCity(string(first(selected.get("providerReturnedCity"), iqAirEvidence.get("providerReturnedCity"))))
                .providerReturnedStation(string(first(selected.get("providerReturnedStation"), iqAirEvidence.get("providerReturnedStation"))))
                .pm25(number(first(pollutants.get("pm25"), pollutants.get("pm2_5"), pollutants.get("pm25"))))
                .pm10(number(pollutants.get("pm10")))
                .no2(number(pollutants.get("no2")))
                .so2(number(pollutants.get("so2")))
                .co(number(pollutants.get("co")))
                .o3(number(pollutants.get("o3")))
                .nh3(number(pollutants.get("nh3")))
                .temperatureCelsius(number(weather.get("temperature")))
                .humidityPercent(number(weather.get("humidity")))
                .pressureHpa(number(weather.get("pressure")))
                .windSpeedMps(firstNumber(wind.get("speed"), weather.get("windSpeed")))
                .windDirectionDegrees(firstNumber(wind.get("direction"), weather.get("windDirection")))
                .rainfallMm(number(weather.get("rainfall")))
                .cloudCoverPercent(number(weather.get("cloudCover")))
                .visibilityMeters(number(weather.get("visibility")))
                .openWeatherAqiIndex(integer(aqi.get("openWeatherAqiIndex")))
                .openWeatherAqiScale(valueOrDefault(string(aqi.get("openWeatherAqiScale")), "OPENWEATHER_1_TO_5"))
                .providerObservedAt(observedAt)
                .weatherObservedAt(weatherObservedAt)
                .ingestedAt(Instant.now())
                .dataOrigin(dataOrigin(provider, standard))
                .dataQualityWarnings(warnings)
                .build();
        validate(snapshot);
        return snapshot;
    }

    private SaveOutcome saveIdempotently(AqiHistoricalSnapshot snapshot) {
        List<String> lookupKeys = new ArrayList<>();
        lookupKeys.add(snapshot.getLocationKey());
        if (snapshot.getLegacyLocationKeys() != null) lookupKeys.addAll(snapshot.getLegacyLocationKeys());
        if (!isBlank(snapshot.getStationKey())) {
            var stationMatch = snapshotRepository.findFirstByStationKeyAndProviderAndAqiStandardAndProviderObservedAt(
                    snapshot.getStationKey(), snapshot.getProvider(), snapshot.getAqiStandard(), snapshot.getProviderObservedAt());
            if (stationMatch.isPresent()) {
                AqiHistoricalSnapshot existing = stationMatch.get();
                boolean changed = mergeMissing(existing, snapshot);
                return new SaveOutcome(changed ? snapshotRepository.save(existing) : existing,
                        changed ? "UPDATED_METADATA" : "ALREADY_EXISTS");
            }
        }
        return snapshotRepository.findFirstByLocationKeyInAndProviderAndAqiStandardAndProviderObservedAt(
                        lookupKeys.stream().distinct().toList(), snapshot.getProvider(), snapshot.getAqiStandard(), snapshot.getProviderObservedAt())
                .map(existing -> {
                    boolean changed = mergeMissing(existing, snapshot);
                    return new SaveOutcome(changed ? snapshotRepository.save(existing) : existing,
                            changed ? "UPDATED_METADATA" : "ALREADY_EXISTS");
                })
                .orElseGet(() -> {
                    try {
                        return new SaveOutcome(snapshotRepository.save(snapshot), "INSERTED");
                    } catch (DuplicateKeyException e) {
                        AqiHistoricalSnapshot existing = snapshotRepository.findFirstByLocationKeyInAndProviderAndAqiStandardAndProviderObservedAt(
                                        lookupKeys.stream().distinct().toList(), snapshot.getProvider(), snapshot.getAqiStandard(), snapshot.getProviderObservedAt())
                                .orElseThrow(() -> e);
                        return new SaveOutcome(existing, "ALREADY_EXISTS");
                    }
                });
    }

    private boolean mergeMissing(AqiHistoricalSnapshot existing, AqiHistoricalSnapshot incoming) {
        boolean changed = false;
        if (existing.getWeatherObservedAt() == null && incoming.getWeatherObservedAt() != null) { existing.setWeatherObservedAt(incoming.getWeatherObservedAt()); changed = true; }
        if (existing.getTemperatureCelsius() == null && incoming.getTemperatureCelsius() != null) { existing.setTemperatureCelsius(incoming.getTemperatureCelsius()); changed = true; }
        if (existing.getHumidityPercent() == null && incoming.getHumidityPercent() != null) { existing.setHumidityPercent(incoming.getHumidityPercent()); changed = true; }
        if (existing.getPm25() == null && incoming.getPm25() != null) { existing.setPm25(incoming.getPm25()); changed = true; }
        if (existing.getPm10() == null && incoming.getPm10() != null) { existing.setPm10(incoming.getPm10()); changed = true; }
        if (existing.getCanonicalLocationKey() == null && incoming.getCanonicalLocationKey() != null) { existing.setCanonicalLocationKey(incoming.getCanonicalLocationKey()); changed = true; }
        if (existing.getLocationKeyVersion() == null && incoming.getLocationKeyVersion() != null) { existing.setLocationKeyVersion(incoming.getLocationKeyVersion()); changed = true; }
        if ((existing.getLegacyLocationKeys() == null || existing.getLegacyLocationKeys().isEmpty()) && incoming.getLegacyLocationKeys() != null) {
            existing.setLegacyLocationKeys(incoming.getLegacyLocationKeys());
            changed = true;
        }
        if (existing.getDataOrigin() == null && incoming.getDataOrigin() != null) { existing.setDataOrigin(incoming.getDataOrigin()); changed = true; }
        if (existing.getStationKey() == null && incoming.getStationKey() != null) { existing.setStationKey(incoming.getStationKey()); changed = true; }
        if (existing.getStationLocationKey() == null && incoming.getStationLocationKey() != null) { existing.setStationLocationKey(incoming.getStationLocationKey()); changed = true; }
        if (changed) existing.setIngestedAt(Instant.now());
        return changed;
    }

    private void registerStationLocation(AqiHistoricalSnapshot snapshot) {
        if (snapshot == null || isBlank(snapshot.getStationLocationKey()) || isBlank(snapshot.getStationName())) {
            return;
        }
        Instant now = Instant.now();
        TrackedAirQualityLocation location = trackedLocationRepository.findByLocationKey(snapshot.getStationLocationKey())
                .orElseGet(() -> TrackedAirQualityLocation.builder()
                        .locationKey(snapshot.getStationLocationKey())
                        .createdAt(now)
                        .trackingEnabled(true)
                        .build());
        location.setLocationKey(snapshot.getStationLocationKey());
        location.setLocationKeyVersion(snapshot.getLocationKeyVersion());
        location.setCanonicalLocationKey(snapshot.getStationLocationKey());
        location.setStationKey(snapshot.getStationKey());
        location.setStationLocationKey(snapshot.getStationLocationKey());
        location.setStationName(snapshot.getStationName());
        location.setProvider(snapshot.getProvider());
        location.setDisplayName(snapshot.getStationName());
        location.setCity(snapshot.getCity());
        location.setState(snapshot.getState());
        location.setCountry(snapshot.getCountry());
        location.setLatitude(snapshot.getStationLatitude());
        location.setLongitude(snapshot.getStationLongitude());
        location.setLastIngestedAt(snapshot.getIngestedAt());
        location.setUpdatedAt(now);
        if (location.getTrackingEnabled() == null) location.setTrackingEnabled(true);
        trackedLocationRepository.save(location);
    }

    private void applyTrackedLocationMetadata(TrackedAirQualityLocation location, LocationRequest request, CanonicalLocationIdentity identity, Instant now) {
        location.setLocationKey(identity.locationKey());
        location.setLocationKeyVersion(identity.keyVersion());
        location.setCanonicalLocationKey(identity.locationKey());
        location.setDisplayName(valueOrDefault(request.displayName(), request.city()));
        location.setCity(request.city());
        location.setState(request.state());
        location.setCountry(request.country());
        location.setLatitude(request.latitude());
        location.setLongitude(request.longitude());
        location.setLastSearchedAt(now);
        location.setUpdatedAt(now);
        if (location.getCreatedAt() == null) location.setCreatedAt(now);
        if (location.getTrackingEnabled() == null) location.setTrackingEnabled(true);
    }

    private void validate(AqiHistoricalSnapshot snapshot) {
        List<String> warnings = snapshot.getDataQualityWarnings() != null ? snapshot.getDataQualityWarnings() : new ArrayList<>();
        if (snapshot.getCurrentAqi() == null) warnings.add("CURRENT_AQI_MISSING");
        if (isBlank(snapshot.getAqiStandard())) warnings.add("AQI_STANDARD_MISSING");
        if (isBlank(snapshot.getProvider())) warnings.add("PROVIDER_MISSING");
        if (snapshot.getProviderObservedAt() == null) warnings.add("PROVIDER_TIMESTAMP_MISSING");
        if (snapshot.getPm25() == null) warnings.add("PM25_MISSING");
        if (snapshot.getPm10() == null) warnings.add("PM10_MISSING");
        if (snapshot.getTemperatureCelsius() == null || snapshot.getHumidityPercent() == null) warnings.add("WEATHER_PARTIAL");
        if (Boolean.TRUE.equals(snapshot.getIsFallback())) warnings.add("FALLBACK_PROVIDER_USED");
        if (snapshot.getStationDistanceKm() != null && snapshot.getStationDistanceKm() > 50) warnings.add("STATION_DISTANCE_HIGH");
        if (snapshot.getProviderObservedAt() != null
                && Duration.between(snapshot.getProviderObservedAt(), Instant.now()).toMinutes() > properties.getStaleAfterMinutes()) {
            warnings.add("PROVIDER_TIMESTAMP_STALE");
        }
        if (!validAqi(snapshot.getCurrentAqi(), snapshot.getAqiStandard())) warnings.add("AQI_OUT_OF_RANGE");
        if (!sensibleWeather(snapshot)) warnings.add("WEATHER_RANGE_WARNING");
        snapshot.setDataQualityWarnings(warnings.stream().distinct().toList());
        if (warnings.contains("CURRENT_AQI_MISSING") || warnings.contains("AQI_STANDARD_MISSING") || warnings.contains("PROVIDER_MISSING")) {
            snapshot.setDataQualityStatus("INVALID");
        } else if (warnings.contains("PROVIDER_TIMESTAMP_STALE")) {
            snapshot.setDataQualityStatus("STALE");
        } else if (!warnings.isEmpty()) {
            snapshot.setDataQualityStatus("PARTIAL");
        } else {
            snapshot.setDataQualityStatus("VALID");
        }
    }

    private boolean validAqi(Integer aqi, String standard) {
        if (aqi == null) return false;
        if ("US_AQI".equalsIgnoreCase(standard) || "INDIA_NAQI".equalsIgnoreCase(standard)) {
            return aqi >= 0 && aqi <= 500;
        }
        return false;
    }

    private boolean sensibleWeather(AqiHistoricalSnapshot snapshot) {
        return inRange(snapshot.getTemperatureCelsius(), -50, 60)
                && inRange(snapshot.getHumidityPercent(), 0, 100)
                && inRange(snapshot.getPressureHpa(), 800, 1200)
                && inRange(snapshot.getWindSpeedMps(), 0, 80);
    }

    private boolean inRange(Double value, double min, double max) {
        return value == null || (value >= min && value <= max);
    }

    private void updateLastIngested(String locationKey, Instant ingestedAt) {
        trackedLocationRepository.findByLocationKey(locationKey).ifPresent(location -> {
            location.setLastIngestedAt(ingestedAt);
            location.setUpdatedAt(Instant.now());
            trackedLocationRepository.save(location);
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Map<String, Object> map) {
        return map != null ? map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private Map<String, Object> pollutantsFromRows(Object value) {
        if (!(value instanceof List<?> rows)) return Map.of();
        Map<String, Object> pollutants = new LinkedHashMap<>();
        for (Object rowValue : rows) {
            Map<String, Object> row = asMap(rowValue);
            String key = string(first(row.get("normalizedPollutant"), row.get("pollutant"))).toLowerCase(Locale.ROOT);
            if ("pm25".equals(key)) key = "pm25";
            if (!key.isBlank()) pollutants.put(key, first(row.get("concentration"), row.get("avg")));
        }
        return pollutants;
    }

    private Instant instant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private Double firstNumber(Object... values) {
        for (Object value : values) {
            Double number = number(value);
            if (number != null) return number;
        }
        return null;
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

    private String string(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void addWarning(AqiHistoricalSnapshot snapshot, String warning) {
        List<String> warnings = new ArrayList<>(snapshot.getDataQualityWarnings() != null ? snapshot.getDataQualityWarnings() : List.of());
        warnings.add(warning);
        snapshot.setDataQualityWarnings(warnings.stream().distinct().toList());
    }

    private String dataOrigin(String provider, String standard) {
        if (isBlank(provider) || isBlank(standard)) {
            return DataOrigin.UNAVAILABLE.name();
        }
        return "CPCB_CAAQMS".equalsIgnoreCase(provider)
                ? DataOrigin.LIVE_OPERATIONAL_HISTORY.name()
                : DataOrigin.OBSERVED.name();
    }

    private String stationKey(String stationName) {
        if (isBlank(stationName)) return null;
        String key = stationName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return key.isBlank() ? null : key;
    }

    public record LocationRequest(String displayName, String city, String state, String country, Double latitude, Double longitude) {
        public LocationRequest normalized() {
            return new LocationRequest(displayName, city, state, country == null || country.isBlank() ? "India" : country, latitude, longitude);
        }
    }

    private record SaveOutcome(AqiHistoricalSnapshot snapshot, String status) {
    }

    public record IngestionResult(AqiHistoricalSnapshot observation, boolean inserted, List<String> warnings, String status,
                                  String trackedLocationStatus) {
        public IngestionResult withTrackedLocationStatus(String status) {
            return new IngestionResult(observation, inserted, warnings, this.status, status);
        }
    }

    public record TrackedLocationOutcome(TrackedAirQualityLocation location, String status) {
    }
}
