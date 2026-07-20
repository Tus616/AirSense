package com.airsense.api.forecast;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class HistoricalForecastReplayService {
    private static final String STANDARD = "INDIA_NAQI";
    private static final String ARCHIVE_ORIGIN = "HISTORICAL_TRAINING_ARCHIVE";
    private static final String ENGINE = "HISTORICAL_PERSISTENCE_REPLAY";
    private static final String MODEL_VERSION = "historical-persistence-v1";
    private static final String TIMEZONE = "Asia/Kolkata";
    private static final Duration ACTUAL_TOLERANCE = Duration.ofMinutes(90);
    private static final List<Integer> HORIZONS = List.of(24, 48, 72);
    private static final PageRequest FIRST_ASC = PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "providerObservedAt"));

    private static final List<ReplayStationDefinition> VERIFIED_STATIONS = List.of(
            new ReplayStationDefinition(
                    "gomti_nagar_lucknow_uppcb",
                    "Gomti Nagar, Lucknow",
                    "CPCB_CAAQMS",
                    List.of("lucknow_gomti_nagar", "gomti_nagar_lucknow_uppcb", "gomti_nagar_lucknow",
                            "in:26.863:80.999", "in:26.868:81.005", "in:26.847:80.946",
                            "india:lucknow:26.847:80.946", "in:lucknow:26.847:80.946")),
            new ReplayStationDefinition(
                    "ito_delhi_cpcb",
                    "ITO, Delhi",
                    "CPCB_CAAQMS",
                    List.of("delhi_ito", "ito_delhi_cpcb", "ito_delhi", "ito",
                            "in:28.629:77.241", "india:delhi:28.629:77.241", "in:delhi:28.629:77.241")),
            new ReplayStationDefinition(
                    "bandra_kurla_complex_mumbai_mpcb",
                    "Bandra Kurla Complex, Mumbai",
                    "CPCB_CAAQMS",
                    List.of("mumbai_bandra_kurla_complex", "bandra_kurla_complex_mumbai_iitm",
                            "bandra_kurla_complex_mumbai_mpcb", "bandra_kurla_complex_mumbai", "bkc_mumbai_iitm",
                            "bkc_mumbai", "in:19.057:72.859", "in:19.066:72.862", "in:19.053:72.856",
                            "in:19.067:72.867", "india:mumbai:19.053:72.856", "india:mumbai:19.067:72.867",
                            "in:mumbai:19.053:72.856", "in:mumbai:19.067:72.867"))
    );

    private final AqiHistoricalSnapshotRepository snapshotRepository;
    private final MongoTemplate mongoTemplate;
    private final Environment environment;

    public List<HistoricalReplayStationResponse> stations() {
        return VERIFIED_STATIONS.stream()
                .map(this::stationResponse)
                .filter(station -> station.getArchiveRowCount() > 0
                        && station.getLatestReplayTimestamp() != null
                        && (station.getValid24hReplayCount() > 0
                        || station.getValid48hReplayCount() > 0
                        || station.getValid72hReplayCount() > 0))
                .toList();
    }

    public HistoricalReplayResponse replay(HistoricalReplayRequest request) {
        String requestedKey = request == null ? "" : clean(request.stationKey());
        Instant issueTime = request == null ? null : request.forecastIssueTime();
        Optional<ReplayStationDefinition> definition = definitionFor(requestedKey);
        if (requestedKey.isBlank() || issueTime == null) {
            return unavailable(requestedKey, issueTime, "REPLAY_INPUT_UNAVAILABLE",
                    "stationKey and forecastIssueTime are required");
        }
        if (definition.isEmpty()) {
            return unavailable(requestedKey, issueTime, "UNSUPPORTED_REPLAY_STATION",
                    "Historical replay supports only Gomti Nagar, ITO, and Bandra Kurla Complex archive stations.");
        }

        ReplayStationDefinition station = definition.get();
        List<AqiHistoricalSnapshot> rawIssueCandidates = snapshotRepository.findReplayIssueSnapshots(
                station.identityKeys(), STANDARD, issueTime, ARCHIVE_ORIGIN);
        List<AqiHistoricalSnapshot> issueCandidates = rawIssueCandidates == null ? List.of() : rawIssueCandidates;
        List<AqiHistoricalSnapshot> validIssueSnapshots = issueCandidates.stream()
                .filter(snapshot -> validIssueSnapshot(station, snapshot, issueTime))
                .toList();
        Optional<AqiHistoricalSnapshot> issueSnapshot = validIssueSnapshots.stream()
                .max((left, right) -> left.getProviderObservedAt().compareTo(right.getProviderObservedAt()));

        if (issueSnapshot.isEmpty()) {
            return unavailable(station.stationKey(), issueTime, "INSUFFICIENT_PRIOR_HISTORY",
                    "No same-station INDIA_NAQI observation was available at or before the replay issue time.")
                    .toBuilder()
                    .stationName(station.stationName())
                    .dataOrigin(ARCHIVE_ORIGIN)
                    .limitations(List.of("No issue-time-or-earlier archive observation matched the supported station."))
                    .build();
        }

        AqiHistoricalSnapshot issue = issueSnapshot.get();
        int predictedAqi = issue.getCurrentAqi();
        double featureCoverage = featureCoverage(issue);
        Map<Integer, Optional<AqiHistoricalSnapshot>> actualSnapshots = new LinkedHashMap<>();
        HORIZONS.forEach(hours -> actualSnapshots.put(hours, findActual(station, issue.getProviderObservedAt().plus(Duration.ofHours(hours)))));
        List<HistoricalReplayHorizonResponse> results = HORIZONS.stream()
                .map(hours -> horizon(station, issue, hours, predictedAqi, featureCoverage, actualSnapshots.getOrDefault(hours, Optional.empty())))
                .toList();
        String replayRunId = replayRunId(station.stationKey(), issue.getProviderObservedAt(), issue.getId());

        return HistoricalReplayResponse.builder()
                .replayRunId(replayRunId)
                .stationKey(station.stationKey())
                .stationName(displayName(station, issue))
                .forecastIssueTime(issueTime)
                .issueObservationTime(issue.getProviderObservedAt())
                .issueTimeAqi(issue.getCurrentAqi())
                .aqiStandard(STANDARD)
                .snapshotId(issue.getId())
                .engine(ENGINE)
                .modelVersion(MODEL_VERSION)
                .promotionStatus("NOT_APPLICABLE")
                .confidence(confidence(featureCoverage, 1))
                .featureCoverage(featureCoverage)
                .status("AVAILABLE")
                .message("Historical replay used only same-station INDIA_NAQI observations available at issue time; future actuals were loaded after prediction for comparison only.")
                .limitations(List.of("No validated historical ML replay model is currently exposed; safe historical persistence replay was used."))
                .dataOrigin(ARCHIVE_ORIGIN)
                .archiveRowCount(matchingSnapshots(station).size())
                .timelineFrames(timelineFrames(station, issue, validIssueSnapshots, actualSnapshots, replayRunId))
                .results(results)
                .horizons(results)
                .build();
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> response = new LinkedHashMap<>();
        String collection = mongoTemplate.getCollectionName(AqiHistoricalSnapshot.class);
        response.put("activeDatabase", mongoTemplate.getDb().getName());
        response.put("activeSpringProfiles", List.of(environment.getActiveProfiles()));
        response.put("archiveCollection", collection);
        response.put("totalArchiveCollectionRows", mongoTemplate.getCollection(collection).countDocuments());
        response.put("requiredAqiStandard", STANDARD);
        response.put("requiredDataOrigin", ARCHIVE_ORIGIN);
        response.put("providerValuesPresent", distinctValues(collection, "provider", 20));
        response.put("aqiStandardValuesPresent", distinctValues(collection, "aqiStandard", 20));
        response.put("dataOriginValuesPresent", distinctValues(collection, "dataOrigin", 20));
        response.put("timestampFieldNames", List.of("providerObservedAt", "weatherObservedAt", "ingestedAt"));
        response.put("timestampFormat", "MongoDB BSON Date mapped to java.time.Instant");
        response.put("earliestProviderObservedAt", aggregateValue(collection, "$min", "providerObservedAt"));
        response.put("latestProviderObservedAt", aggregateValue(collection, "$max", "providerObservedAt"));
        response.put("supportedStations", stations());
        response.put("stationDiagnostics", VERIFIED_STATIONS.stream().map(definition -> {
            List<AqiHistoricalSnapshot> observations = matchingSnapshots(definition);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stationKey", definition.stationKey());
            item.put("aliases", definition.identityKeys());
            item.put("archiveRowCount", observations.size());
            item.put("providerValues", observations.stream().map(AqiHistoricalSnapshot::getProvider).filter(v -> v != null && !v.isBlank()).distinct().toList());
            item.put("aqiStandardValues", observations.stream().map(AqiHistoricalSnapshot::getAqiStandard).filter(v -> v != null && !v.isBlank()).distinct().toList());
            item.put("stationKeyValues", observations.stream().map(AqiHistoricalSnapshot::getStationKey).filter(v -> v != null && !v.isBlank()).distinct().toList());
            item.put("stationNameValues", observations.stream().map(AqiHistoricalSnapshot::getStationName).filter(v -> v != null && !v.isBlank()).distinct().toList());
            item.put("locationKeyValues", observations.stream().map(AqiHistoricalSnapshot::getLocationKey).filter(v -> v != null && !v.isBlank()).distinct().toList());
            item.put("stationLocationKeyValues", observations.stream().map(AqiHistoricalSnapshot::getStationLocationKey).filter(v -> v != null && !v.isBlank()).distinct().toList());
            item.put("dataOriginValues", observations.stream().map(AqiHistoricalSnapshot::getDataOrigin).filter(v -> v != null && !v.isBlank()).distinct().toList());
            item.put("earliestTimestamp", observations.stream().map(AqiHistoricalSnapshot::getProviderObservedAt).filter(v -> v != null).min(Instant::compareTo).orElse(null));
            item.put("latestTimestamp", observations.stream().map(AqiHistoricalSnapshot::getProviderObservedAt).filter(v -> v != null).max(Instant::compareTo).orElse(null));
            return item;
        }).toList());
        response.put("rejectedRowCountsByReason", Map.of(
                "DIFFERENT_DATA_ORIGIN", countNotEqual(collection, "dataOrigin", ARCHIVE_ORIGIN),
                "DIFFERENT_AQI_STANDARD", countNotEqual(collection, "aqiStandard", STANDARD),
                "MISSING_PROVIDER_OBSERVED_AT", countMissing(collection, "providerObservedAt"),
                "MISSING_CURRENT_AQI", countMissing(collection, "currentAqi")
        ));
        return response;
    }

    private HistoricalReplayStationResponse stationResponse(ReplayStationDefinition definition) {
        List<AqiHistoricalSnapshot> observations = matchingSnapshots(definition);
        Instant earliest = observations.stream().map(AqiHistoricalSnapshot::getProviderObservedAt)
                .filter(time -> time != null).min(Instant::compareTo).orElse(null);
        Instant latestObservation = observations.stream().map(AqiHistoricalSnapshot::getProviderObservedAt)
                .filter(time -> time != null).max(Instant::compareTo).orElse(null);
        Instant latestReplay = latestObservation == null ? null : latestObservation.minus(Duration.ofHours(72));
        if (earliest != null && latestReplay != null && latestReplay.isBefore(earliest)) {
            latestReplay = null;
        }

        String discoveredName = observations.stream()
                .map(snapshot -> displayName(definition, snapshot))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(definition.stationName());

        return HistoricalReplayStationResponse.builder()
                .stationKey(definition.stationKey())
                .stationName(discoveredName)
                .provider(definition.provider())
                .aqiStandard(STANDARD)
                .earliestReplayTimestamp(earliest)
                .latestReplayTimestamp(latestReplay)
                .earliestValidReplayTimestamp(earliest)
                .latestValidReplayTimestamp(latestReplay)
                .valid24hReplayCount(countReplayable(definition, observations, 24))
                .valid48hReplayCount(countReplayable(definition, observations, 48))
                .valid72hReplayCount(countReplayable(definition, observations, 72))
                .archiveRowCount(observations.size())
                .timezone(TIMEZONE)
                .dataOrigin(ARCHIVE_ORIGIN)
                .status(observations.isEmpty() ? "NO_VERIFIED_ARCHIVE_ROWS" : "AVAILABLE")
                .build();
    }

    private HistoricalReplayHorizonResponse horizon(ReplayStationDefinition station, AqiHistoricalSnapshot issue,
                                                    int hours, Integer predictedAqi, double featureCoverage,
                                                    Optional<AqiHistoricalSnapshot> actual) {
        Instant target = issue.getProviderObservedAt().plus(Duration.ofHours(hours));
        Integer actualAqi = actual.map(AqiHistoricalSnapshot::getCurrentAqi).orElse(null);
        Double absoluteError = actualAqi == null || predictedAqi == null ? null : Math.abs((double) predictedAqi - actualAqi);
        Double percentageError = actualAqi == null || actualAqi == 0 || absoluteError == null ? null : absoluteError / actualAqi * 100.0;

        return HistoricalReplayHorizonResponse.builder()
                .horizonHours(hours)
                .targetTime(target)
                .predictedAqi(predictedAqi)
                .actualAqi(actualAqi)
                .actualObservationTimestamp(actual.map(AqiHistoricalSnapshot::getProviderObservedAt).orElse(null))
                .actualObservedAt(actual.map(AqiHistoricalSnapshot::getProviderObservedAt).orElse(null))
                .absoluteError(absoluteError)
                .percentageError(percentageError)
                .engine(ENGINE)
                .modelVersion(MODEL_VERSION)
                .promotionStatus("NOT_APPLICABLE")
                .confidence(confidence(featureCoverage, historyCoverageHours(issue)))
                .featureCoverage(featureCoverage)
                .historyCoverageHours(historyCoverageHours(issue))
                .fallbackReason("SAFE_HISTORICAL_PERSISTENCE_REPLAY")
                .build();
    }

    private Optional<AqiHistoricalSnapshot> findActual(ReplayStationDefinition station, Instant target) {
        List<AqiHistoricalSnapshot> candidates = snapshotRepository.findReplayActualSnapshots(
                station.identityKeys(), STANDARD, target.minus(ACTUAL_TOLERANCE), target.plus(ACTUAL_TOLERANCE),
                ARCHIVE_ORIGIN, FIRST_ASC);
        return (candidates == null ? List.<AqiHistoricalSnapshot>of() : candidates).stream()
                .filter(snapshot -> validActualSnapshot(station, snapshot, target))
                .findFirst();
    }

    private List<AqiHistoricalSnapshot> matchingSnapshots(ReplayStationDefinition definition) {
        List<AqiHistoricalSnapshot> snapshots = snapshotRepository.findReplaySnapshotsByIdentityKeysAndStandard(
                definition.identityKeys(), STANDARD, ARCHIVE_ORIGIN, Sort.by(Sort.Direction.ASC, "providerObservedAt"));
        return (snapshots == null ? List.<AqiHistoricalSnapshot>of() : snapshots)
                .stream()
                .filter(snapshot -> validStationAndStandard(definition, snapshot))
                .filter(snapshot -> snapshot.getProviderObservedAt() != null && snapshot.getCurrentAqi() != null)
                .toList();
    }

    private List<HistoricalReplayTimelineFrameResponse> timelineFrames(ReplayStationDefinition station,
                                                                       AqiHistoricalSnapshot issue,
                                                                       List<AqiHistoricalSnapshot> issueCandidates,
                                                                       Map<Integer, Optional<AqiHistoricalSnapshot>> actualSnapshots,
                                                                       String replayRunId) {
        Optional<AqiHistoricalSnapshot> prior = issueCandidates.stream()
                .filter(snapshot -> snapshot.getProviderObservedAt() != null)
                .filter(snapshot -> snapshot.getProviderObservedAt().isBefore(issue.getProviderObservedAt()))
                .max((left, right) -> left.getProviderObservedAt().compareTo(right.getProviderObservedAt()));
        List<HistoricalReplayTimelineFrameResponse> frames = new ArrayList<>();
        frames.add(timelineFrame(station, replayRunId, "Historical", -1, prior.orElse(null), null));
        frames.add(timelineFrame(station, replayRunId, "Current", 0, issue, issue.getProviderObservedAt()));
        HORIZONS.forEach(hours -> frames.add(timelineFrame(
                station,
                replayRunId,
                "+" + hours + "h",
                hours,
                actualSnapshots.getOrDefault(hours, Optional.empty()).orElse(null),
                issue.getProviderObservedAt().plus(Duration.ofHours(hours)))));
        return frames;
    }

    private HistoricalReplayTimelineFrameResponse timelineFrame(ReplayStationDefinition station, String replayRunId,
                                                                String label, Integer offsetHours,
                                                                AqiHistoricalSnapshot snapshot, Instant targetTime) {
        if (snapshot == null) {
            return unavailableTimelineFrame(station.stationKey(), station.stationName(), replayRunId, label, offsetHours, targetTime);
        }
        String stationName = displayName(station, snapshot);
        String source = firstNonBlank(snapshot.getPrimaryPollutant(), snapshot.getProvider(), snapshot.getDataOrigin(), ARCHIVE_ORIGIN);
        return HistoricalReplayTimelineFrameResponse.builder()
                .frameId(frameId(replayRunId, label))
                .replayRunId(replayRunId)
                .label(label)
                .offsetHours(offsetHours)
                .stationKey(station.stationKey())
                .stationName(stationName)
                .timestamp(snapshot.getProviderObservedAt())
                .targetTime(targetTime != null ? targetTime : snapshot.getProviderObservedAt())
                .aqi(snapshot.getCurrentAqi())
                .risk(risk(snapshot.getCurrentAqi(), snapshot.getAqiCategory()))
                .dominantSource(source)
                .source(source)
                .pollutants(pollutants(snapshot))
                .weather(weather(snapshot))
                .provider(snapshot.getProvider())
                .dataOrigin(snapshot.getDataOrigin())
                .aqiStandard(snapshot.getAqiStandard())
                .snapshotId(snapshot.getId())
                .unavailable(false)
                .message("Historical frame loaded from replay archive.")
                .summary(label + " archive observation for " + stationName + " at " + snapshot.getProviderObservedAt() + ".")
                .build();
    }

    private HistoricalReplayTimelineFrameResponse unavailableTimelineFrame(String stationKey, String stationName,
                                                                          String replayRunId, String label,
                                                                          Integer offsetHours, Instant targetTime) {
        return HistoricalReplayTimelineFrameResponse.builder()
                .frameId(frameId(replayRunId, label))
                .replayRunId(replayRunId)
                .label(label)
                .offsetHours(offsetHours)
                .stationKey(stationKey)
                .stationName(stationName)
                .targetTime(targetTime)
                .aqiStandard(STANDARD)
                .dataOrigin(ARCHIVE_ORIGIN)
                .risk(new LinkedHashMap<>())
                .pollutants(new LinkedHashMap<>())
                .weather(new LinkedHashMap<>())
                .unavailable(true)
                .message("Historical frame unavailable")
                .summary("Historical frame unavailable")
                .build();
    }

    private List<HistoricalReplayTimelineFrameResponse> unavailableTimelineFrames(String stationKey, Instant issueTime, String replayRunId) {
        List<HistoricalReplayTimelineFrameResponse> frames = new ArrayList<>();
        frames.add(unavailableTimelineFrame(stationKey, null, replayRunId, "Historical", -1, null));
        frames.add(unavailableTimelineFrame(stationKey, null, replayRunId, "Current", 0, issueTime));
        HORIZONS.forEach(hours -> frames.add(unavailableTimelineFrame(
                stationKey,
                null,
                replayRunId,
                "+" + hours + "h",
                hours,
                issueTime == null ? null : issueTime.plus(Duration.ofHours(hours)))));
        return frames;
    }

    private Map<String, Object> risk(Integer aqi, String category) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("overallRiskLevel", riskLevel(aqi, category));
        values.put("aqiCategory", firstNonBlank(category, riskLevel(aqi, category)));
        return values;
    }

    private String riskLevel(Integer aqi, String category) {
        String normalized = clean(category).toUpperCase(Locale.ROOT);
        if (!normalized.isBlank()) return normalized;
        if (aqi == null) return "UNKNOWN";
        if (aqi <= 50) return "GOOD";
        if (aqi <= 100) return "SATISFACTORY";
        if (aqi <= 200) return "MODERATE";
        if (aqi <= 300) return "POOR";
        if (aqi <= 400) return "VERY_POOR";
        return "SEVERE";
    }

    private Map<String, Object> pollutants(AqiHistoricalSnapshot snapshot) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("pm25", snapshot.getPm25());
        values.put("pm10", snapshot.getPm10());
        values.put("no2", snapshot.getNo2());
        values.put("so2", snapshot.getSo2());
        values.put("co", snapshot.getCo());
        values.put("o3", snapshot.getO3());
        values.put("nh3", snapshot.getNh3());
        values.put("primaryPollutant", snapshot.getPrimaryPollutant());
        return values;
    }

    private Map<String, Object> weather(AqiHistoricalSnapshot snapshot) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("temperatureCelsius", snapshot.getTemperatureCelsius());
        values.put("humidityPercent", snapshot.getHumidityPercent());
        values.put("pressureHpa", snapshot.getPressureHpa());
        values.put("windSpeedMps", snapshot.getWindSpeedMps());
        values.put("windDirectionDegrees", snapshot.getWindDirectionDegrees());
        values.put("rainfallMm", snapshot.getRainfallMm());
        values.put("cloudCoverPercent", snapshot.getCloudCoverPercent());
        values.put("visibilityMeters", snapshot.getVisibilityMeters());
        values.put("weatherObservedAt", snapshot.getWeatherObservedAt());
        return values;
    }

    private int countReplayable(ReplayStationDefinition definition, List<AqiHistoricalSnapshot> observations, int hours) {
        TreeSet<Instant> timestamps = new TreeSet<>();
        observations.stream()
                .map(AqiHistoricalSnapshot::getProviderObservedAt)
                .filter(time -> time != null)
                .forEach(timestamps::add);
        int count = 0;
        for (AqiHistoricalSnapshot observation : observations) {
            if (observation.getProviderObservedAt() == null) continue;
            Instant target = observation.getProviderObservedAt().plus(Duration.ofHours(hours));
            Instant earliest = target.minus(ACTUAL_TOLERANCE);
            Instant latest = target.plus(ACTUAL_TOLERANCE);
            Instant candidate = timestamps.ceiling(earliest);
            if (candidate != null && !candidate.isAfter(latest)) {
                count++;
            }
        }
        return count;
    }

    private boolean validIssueSnapshot(ReplayStationDefinition definition, AqiHistoricalSnapshot snapshot, Instant issueTime) {
        return validStationAndStandard(definition, snapshot)
                && snapshot.getProviderObservedAt() != null
                && !snapshot.getProviderObservedAt().isAfter(issueTime)
                && snapshot.getCurrentAqi() != null;
    }

    private boolean validActualSnapshot(ReplayStationDefinition definition, AqiHistoricalSnapshot snapshot, Instant target) {
        if (!validStationAndStandard(definition, snapshot) || snapshot.getProviderObservedAt() == null || snapshot.getCurrentAqi() == null) {
            return false;
        }
        Duration distance = Duration.between(target, snapshot.getProviderObservedAt()).abs();
        return !distance.minus(ACTUAL_TOLERANCE).isPositive();
    }

    private boolean validStationAndStandard(ReplayStationDefinition definition, AqiHistoricalSnapshot snapshot) {
        if (snapshot == null || !STANDARD.equals(snapshot.getAqiStandard()) || !ARCHIVE_ORIGIN.equals(snapshot.getDataOrigin())) return false;
        Set<String> keys = definition.identityKeySet();
        return keys.contains(normalize(snapshot.getStationKey()))
                || keys.contains(normalize(snapshot.getStationLocationKey()))
                || keys.contains(normalize(snapshot.getLocationKey()));
    }

    private double featureCoverage(AqiHistoricalSnapshot snapshot) {
        List<Object> features = Arrays.asList(
                snapshot.getCurrentAqi(),
                snapshot.getPm25(),
                snapshot.getPm10(),
                snapshot.getNo2(),
                snapshot.getSo2(),
                snapshot.getCo(),
                snapshot.getO3(),
                snapshot.getNh3(),
                snapshot.getTemperatureCelsius(),
                snapshot.getHumidityPercent(),
                snapshot.getWindSpeedMps()
        );
        long available = features.stream().filter(value -> value != null).count();
        return Math.round((available * 10000.0) / features.size()) / 100.0;
    }

    private double confidence(double featureCoverage, double historyCoverageHours) {
        double value = Math.min(0.65, 0.20 + (featureCoverage / 100.0 * 0.30) + Math.min(historyCoverageHours, 24.0) / 24.0 * 0.15);
        return Math.round(value * 100.0) / 100.0;
    }

    private double historyCoverageHours(AqiHistoricalSnapshot issue) {
        return issue.getProviderObservedAt() == null ? 0.0 : 1.0;
    }

    private String displayName(ReplayStationDefinition definition, AqiHistoricalSnapshot snapshot) {
        return Stream.of(snapshot.getStationName(), snapshot.getProviderReturnedStation(), definition.stationName())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(definition.stationName());
    }

    private Optional<ReplayStationDefinition> definitionFor(String stationKey) {
        String normalized = normalize(stationKey);
        return VERIFIED_STATIONS.stream()
                .filter(station -> station.identityKeySet().contains(normalized))
                .findFirst();
    }

    private HistoricalReplayResponse unavailable(String stationKey, Instant issueTime, String fallbackReason, String message) {
        String replayRunId = replayRunId(stationKey, issueTime, fallbackReason);
        List<HistoricalReplayHorizonResponse> rows = HORIZONS.stream()
                .map(hours -> HistoricalReplayHorizonResponse.builder()
                        .horizonHours(hours)
                        .predictedAqi(null)
                        .actualAqi(null)
                        .absoluteError(null)
                        .percentageError(null)
                        .engine(null)
                        .modelVersion(null)
                        .promotionStatus(null)
                        .confidence(null)
                        .featureCoverage(null)
                        .historyCoverageHours(0.0)
                        .fallbackReason(fallbackReason)
                        .build())
                .toList();
        return HistoricalReplayResponse.builder()
                .replayRunId(replayRunId)
                .stationKey(stationKey)
                .forecastIssueTime(issueTime)
                .aqiStandard(STANDARD)
                .status("UNAVAILABLE")
                .message(message)
                .limitations(List.of(message))
                .dataOrigin(ARCHIVE_ORIGIN)
                .timelineFrames(unavailableTimelineFrames(stationKey, issueTime, replayRunId))
                .results(rows)
                .horizons(rows)
                .build();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .map(this::clean)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String replayRunId(String stationKey, Instant issueObservationTime, String snapshotId) {
        return normalize(stationKey) + ":" + (issueObservationTime == null ? "unknown" : issueObservationTime) + ":" + normalize(snapshotId);
    }

    private String frameId(String replayRunId, String label) {
        return normalize(replayRunId).replaceAll("[^a-z0-9]+", "-") + "-" + normalize(label).replaceAll("[^a-z0-9]+", "-");
    }

    private List<String> distinctValues(String collection, String field, int limit) {
        return mongoTemplate.getCollection(collection)
                .distinct(field, String.class)
                .into(new ArrayList<>())
                .stream()
                .limit(limit)
                .toList();
    }

    private Object aggregateValue(String collection, String operator, String field) {
        List<Document> result = mongoTemplate.getCollection(collection).aggregate(List.of(
                new Document("$group", new Document("_id", null).append("value", new Document(operator, "$" + field)))
        )).into(new ArrayList<>());
        return result.isEmpty() ? null : result.get(0).get("value");
    }

    private long countNotEqual(String collection, String field, String expected) {
        return mongoTemplate.getCollection(collection).countDocuments(new Document(field, new Document("$ne", expected)));
    }

    private long countMissing(String collection, String field) {
        return mongoTemplate.getCollection(collection).countDocuments(new Document("$or", List.of(
                new Document(field, new Document("$exists", false)),
                new Document(field, null)
        )));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ReplayStationDefinition(String stationKey, String stationName, String provider, List<String> aliases) {
        List<String> identityKeys() {
            return identityKeySet().stream().toList();
        }

        Set<String> identityKeySet() {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            keys.add(normalize(stationKey));
            aliases.forEach(alias -> keys.add(normalize(alias)));
            return keys;
        }
    }

    public record HistoricalReplayRequest(String stationKey, Instant forecastIssueTime) {
    }

    @Data
    @Builder
    public static class HistoricalReplayStationResponse {
        private String stationKey;
        private String stationName;
        private String provider;
        private String aqiStandard;
        private Instant earliestReplayTimestamp;
        private Instant latestReplayTimestamp;
        private Instant earliestValidReplayTimestamp;
        private Instant latestValidReplayTimestamp;
        private int valid24hReplayCount;
        private int valid48hReplayCount;
        private int valid72hReplayCount;
        private String timezone;
        private String dataOrigin;
        private int archiveRowCount;
        private String status;
    }

    @Data
    @Builder
    public static class HistoricalReplayHorizonResponse {
        private int horizonHours;
        private Instant targetTime;
        private Integer predictedAqi;
        private Integer actualAqi;
        private Double absoluteError;
        private Double percentageError;
        private String engine;
        private String modelVersion;
        private String promotionStatus;
        private Double confidence;
        private Double featureCoverage;
        private Double historyCoverageHours;
        private String fallbackReason;
        private Instant actualObservationTimestamp;
        private Instant actualObservedAt;
    }

    @Data
    @Builder
    public static class HistoricalReplayTimelineFrameResponse {
        private String frameId;
        private String replayRunId;
        private String label;
        private Integer offsetHours;
        private String stationKey;
        private String stationName;
        private Instant timestamp;
        private Instant targetTime;
        private Integer aqi;
        @Builder.Default
        private Map<String, Object> risk = new LinkedHashMap<>();
        private String dominantSource;
        private String source;
        @Builder.Default
        private Map<String, Object> pollutants = new LinkedHashMap<>();
        @Builder.Default
        private Map<String, Object> weather = new LinkedHashMap<>();
        private String provider;
        private String dataOrigin;
        private String aqiStandard;
        private String snapshotId;
        private Boolean unavailable;
        private String message;
        private String summary;
    }

    @Data
    @Builder(toBuilder = true)
    public static class HistoricalReplayResponse {
        private String replayRunId;
        private String stationKey;
        private String stationName;
        private Instant forecastIssueTime;
        private Instant issueObservationTime;
        private Integer issueTimeAqi;
        private String aqiStandard;
        @Builder.Default
        private List<HistoricalReplayHorizonResponse> results = new ArrayList<>();
        @Builder.Default
        private List<HistoricalReplayHorizonResponse> horizons = new ArrayList<>();
        @Builder.Default
        private List<String> limitations = new ArrayList<>();
        @Builder.Default
        private List<HistoricalReplayTimelineFrameResponse> timelineFrames = new ArrayList<>();
        private String dataOrigin;
        private int archiveRowCount;
        private String snapshotId;
        private String engine;
        private String modelVersion;
        private String promotionStatus;
        private Double confidence;
        private Double featureCoverage;
        private String status;
        private String message;
    }
}
