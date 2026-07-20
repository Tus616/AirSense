package com.airsense.api.attribution;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PollutionSourceAttributionEngine {
    private static final double LOW_CONFIDENCE = 0.18;
    private final SourceAttributionProperties properties;

    public AttributionResult calculate(PollutionAttributionInput input) {
        PollutionAttributionInput safe = input != null ? input : PollutionAttributionInput.builder().build();
        Instant generatedAt = safe.getGeneratedAt() != null ? safe.getGeneratedAt() : Instant.now();
        if (!hasCurrentAqi(safe)) {
            return unavailable(safe, generatedAt, "CURRENT_AQI_UNAVAILABLE");
        }
        Map<PollutionSourceType, Score> scores = new EnumMap<>(PollutionSourceType.class);
        for (PollutionSourceType source : PollutionSourceType.values()) {
            if (source != PollutionSourceType.UNKNOWN) scores.put(source, new Score(source, generatedAt));
        }
        scoreTraffic(scores.get(PollutionSourceType.TRAFFIC), safe);
        scoreRoadDustConstruction(scores.get(PollutionSourceType.ROAD_DUST_CONSTRUCTION), safe);
        scoreIndustrial(scores.get(PollutionSourceType.INDUSTRIAL), safe);
        scoreBurning(scores.get(PollutionSourceType.BIOMASS_WASTE_BURNING), safe);
        scoreRegional(scores.get(PollutionSourceType.REGIONAL_TRANSPORT), safe);
        scoreResidential(scores.get(PollutionSourceType.RESIDENTIAL_COMBUSTION), safe);
        scoreOther(scores.get(PollutionSourceType.SECONDARY_AEROSOL_OR_OTHER), safe);

        Map<String, Object> coverage = evidenceCoverage(safe);
        int unknown = unknownShare(scores, coverage);
        List<PollutionSourceContribution> contributions = normalize(scores, safe, unknown, generatedAt);
        PollutionSourceContribution dominant = contributions.stream()
                .filter(item -> item.getSourceType() != PollutionSourceType.UNKNOWN)
                .max(Comparator.comparingInt(PollutionSourceContribution::getContributionPercent))
                .orElse(contributions.stream().filter(item -> item.getSourceType() == PollutionSourceType.UNKNOWN).findFirst().orElse(null));
        double overallConfidence = weightedConfidence(contributions);
        Map<String, Double> rawScores = rawScores(scores);
        Map<String, Object> diagnostics = diagnostics(safe, scores, unknown, contributions);
        List<String> warnings = warnings(safe, coverage);
        String explanation = dominant == null || dominant.getSourceType() == PollutionSourceType.UNKNOWN
                ? "Source attribution is unresolved; UNKNOWN is retained instead of forcing a false dominant source."
                : "Evidence-fusion estimate points to " + dominant.getDisplayName() + " at "
                + dominant.getContributionPercent() + "%, with " + unknown
                + "% retained as UNKNOWN because evidence is incomplete.";

        return AttributionResult.builder()
                .status("SUCCESS")
                .city(string(safe.getLocation().get("city")))
                .cityId(string(safe.getLocation().get("city")))
                .location(safe.getLocation())
                .currentAqi(safe.getCurrentAqi())
                .method(method())
                .timestamp(generatedAt)
                .generatedAt(generatedAt)
                .snapshotId(safe.getSnapshotId())
                .locationKey(safe.getLocationKey())
                .snapshotObservedAt(safe.getSnapshotObservedAt())
                .snapshotGeneratedAt(safe.getSnapshotGeneratedAt())
                .snapshotReused(safe.getSnapshotReused())
                .locationHash(safe.getLocationHash())
                .sources(contributions)
                .dominantSource(dominant != null ? dominant.getSourceType() : PollutionSourceType.UNKNOWN)
                .overallConfidence(round(overallConfidence))
                .overallConfidenceLabel(AttributionConfidenceLabel.from(overallConfidence).name())
                .unknownContributionPercent(unknown)
                .explanation(explanation)
                .calculationMethod(SourceScoringRule.ENGINE)
                .rawScores(rawScores)
                .evidenceCoverage(coverage)
                .diagnostics(diagnostics)
                .providerStatus(providerStatus(safe))
                .warnings(warnings)
                .build();
    }

    private void scoreTraffic(Score score, PollutionAttributionInput input) {
        double no2 = number(input.getPollutants().get("no2"));
        double co = number(input.getPollutants().get("co"));
        double roadDensity = number(input.getGeospatial().get("roadDensityKmPerSquareKm"));
        double nearestRoad = number(input.getGeospatial().get("nearestMajorRoadDistanceKm"));
        if (no2 >= properties.getElevatedNo2()) score.support(properties.getTrafficNo2Weight(), "ELEVATED_NO2", no2, "ug/m3", "CPCB_CAAQMS", "Elevated NO2 is compatible with vehicle emissions.", "POLLUTANT_FINGERPRINT");
        else if (has(input.getPollutants(), "no2")) score.contradict(properties.getTrafficLowNo2Penalty(), "LOW_NO2", no2, "ug/m3", "CPCB_CAAQMS", "NO2 is not elevated for a traffic-heavy explanation.", "POLLUTANT_FINGERPRINT");
        else score.missing("NO2_UNAVAILABLE");
        if (co >= properties.getElevatedCo()) score.support(properties.getTrafficCoWeight(), "ELEVATED_CO", co, "mg/m3", "CPCB_CAAQMS", "CO is elevated, which can support combustion and traffic evidence.", "POLLUTANT_FINGERPRINT");
        else if (!has(input.getPollutants(), "co")) score.missing("CO_UNAVAILABLE");
        if (roadDensity >= properties.getHighRoadDensityKmPerSqKm()) score.support(properties.getTrafficRoadDensityWeight(), "HIGH_ROAD_DENSITY", roadDensity, "km/km2", "OPENSTREETMAP_OVERPASS", "OSM road density is an infrastructure proxy, not live traffic.", "INFRASTRUCTURE_PROXY");
        else if (available(input.getGeospatial())) score.contradict(properties.getTrafficLowRoadDensityPenalty(), "LOW_ROAD_DENSITY", roadDensity, "km/km2", "OPENSTREETMAP_OVERPASS", "Mapped road density is low around the searched coordinates.", "INFRASTRUCTURE_PROXY");
        else score.missing("OSM_ROAD_EVIDENCE_UNAVAILABLE");
        if (nearestRoad > 0 && nearestRoad <= properties.getNearMajorRoadKm()) score.support(properties.getTrafficNearestRoadWeight(), "NEAR_MAJOR_ROAD", nearestRoad, "km", "OPENSTREETMAP_OVERPASS", "A major road is close to the searched coordinates.", "INFRASTRUCTURE_PROXY");
        if (Boolean.TRUE.equals(input.getTemporal().get("isRushHour"))) score.support(properties.getTrafficRushHourWeight(), "RUSH_HOUR", input.getTemporal().get("localHour"), "hour", "SYSTEM_CLOCK", "Observation time falls in a local rush-hour window.", "TEMPORAL_PATTERN");
        if (wind(input) > 0 && wind(input) <= properties.getLowWindMps()) score.support(properties.getTrafficLowWindWeight(), "LOW_WIND_STAGNATION", wind(input), "m/s", "OPENWEATHER", "Low wind can retain road-adjacent emissions.", "METEOROLOGY");
        if (rain(input) > properties.getRainfallWashoutMm()) score.contradict(properties.getTrafficRainPenalty(), "RAINFALL_WASHOUT", rain(input), "mm", "OPENWEATHER", "Rain weakens near-road dust and traffic-resuspension attribution.", "METEOROLOGY");
        if (!score.hasSignalType("POLLUTANT_FINGERPRINT")) score.capRawScore(properties.getTrafficProxyOnlyMaxScore());
    }

    private void scoreRoadDustConstruction(Score score, PollutionAttributionInput input) {
        double pm25 = number(input.getPollutants().get("pm25"));
        double pm10 = number(input.getPollutants().get("pm10"));
        double humidity = number(input.getWeather().get("humidityPercent"));
        double roadDensity = number(input.getGeospatial().get("roadDensityKmPerSquareKm"));
        int construction = (int) number(input.getGeospatial().get("constructionSiteCount"));
        if (pm25 > 0 && pm10 / pm25 >= properties.getPm10DominanceRatio()) score.support(0.80, "PM10_DOMINANCE", round(pm10 / pm25), "ratio", "CPCB_CAAQMS", "PM10 dominates PM2.5, compatible with coarse dust.", "POLLUTANT_FINGERPRINT");
        else if (pm25 > 0 && pm10 > 0) score.contradict(0.30, "PM25_DOMINANCE", round(pm10 / pm25), "ratio", "CPCB_CAAQMS", "PM2.5 dominates, weakening road-dust construction evidence.", "POLLUTANT_FINGERPRINT");
        if (pm10 - pm25 >= 50) score.support(0.38, "PM10_PM25_GAP", round(pm10 - pm25), "ug/m3", "CPCB_CAAQMS", "Large PM10 minus PM2.5 gap supports coarse-particle contribution.", "POLLUTANT_FINGERPRINT");
        if (humidity > 0 && humidity <= properties.getDryHumidityPercent()) score.support(0.32, "DRY_CONDITIONS", humidity, "%", "OPENWEATHER", "Dry air supports dust resuspension.", "METEOROLOGY");
        if (rain(input) > properties.getRainfallWashoutMm()) score.contradict(0.55, "RAINFALL_WASHOUT", rain(input), "mm", "OPENWEATHER", "Recent rainfall weakens dust attribution.", "METEOROLOGY");
        if (construction > 0) score.support(0.54, "CONSTRUCTION_GEOMETRY", construction, "features", "CONFIGURED_GEOJSON", "Configured construction geometry exists near the searched coordinates.", "REAL_GEOMETRY");
        else if (available(input.getGeospatial())) score.contradict(0.18, "NO_CONSTRUCTION_GEOMETRY", 0, "features", "CONFIGURED_GEOJSON", "No configured construction geometry was available nearby.", "REAL_GEOMETRY");
        if (roadDensity >= properties.getHighRoadDensityKmPerSqKm()) score.support(0.25, "ROAD_DUST_PROXY", roadDensity, "km/km2", "OPENSTREETMAP_OVERPASS", "Road density is a road-dust proxy, not a direct dust measurement.", "INFRASTRUCTURE_PROXY");
    }

    private void scoreIndustrial(Score score, PollutionAttributionInput input) {
        int industrial = (int) number(input.getGeospatial().get("industrialFeatureCount"));
        double so2 = number(input.getPollutants().get("so2"));
        double no2 = number(input.getPollutants().get("no2"));
        double co = number(input.getPollutants().get("co"));
        if (industrial > 0) score.support(0.76, "INDUSTRIAL_GEOMETRY", industrial, "features", "CONFIGURED_GEOJSON", "Real industrial geometry exists near the searched coordinates.", "REAL_GEOMETRY");
        else {
            if (available(input.getGeospatial())) score.contradict(0.50, "NO_INDUSTRIAL_GEOMETRY", 0, "features", "CONFIGURED_GEOJSON", "No real industrial geometry was available; industrial contribution is not forced.", "REAL_GEOMETRY");
            return;
        }
        if (so2 >= properties.getElevatedSo2()) score.support(0.65, "ELEVATED_SO2", so2, "ug/m3", "CPCB_CAAQMS", "Elevated SO2 supports combustion or industrial-source evidence.", "POLLUTANT_FINGERPRINT");
        else if (has(input.getPollutants(), "so2")) score.contradict(0.24, "LOW_SO2", so2, "ug/m3", "CPCB_CAAQMS", "SO2 is not elevated for industrial marker evidence.", "POLLUTANT_FINGERPRINT");
        if (no2 >= properties.getElevatedNo2()) score.support(0.25, "ELEVATED_NO2", no2, "ug/m3", "CPCB_CAAQMS", "NO2 can support combustion-source evidence alongside real industrial geometry.", "POLLUTANT_FINGERPRINT");
        if (co >= properties.getElevatedCo()) score.support(0.20, "ELEVATED_CO", co, "mg/m3", "CPCB_CAAQMS", "CO supports combustion-source evidence alongside real industrial geometry.", "POLLUTANT_FINGERPRINT");
        if (industrial == 0) score.capRawScore(0.35);
    }

    private void scoreBurning(Score score, PollutionAttributionInput input) {
        boolean fireAvailable = available(input.getFireEvidence());
        double fireInfluence = number(input.getFireEvidence().get("aggregateFireInfluenceScore"));
        int relevantFires = (int) number(input.getFireEvidence().get("relevantDetectionCount"));
        double pm25 = number(input.getPollutants().get("pm25"));
        double co = number(input.getPollutants().get("co"));
        int landfill = (int) number(input.getGeospatial().get("landfillCount"));
        if (fireAvailable && relevantFires > 0 && fireInfluence >= 0.25) {
            score.support(0.90 * fireInfluence, "RELEVANT_FIRE_DETECTIONS", relevantFires, "detections", "NASA_FIRMS", "Active-fire detections have distance, recency and wind-alignment support.", "REMOTE_SENSING_FIRE");
        } else {
            score.contradict(0.35, "NO_RELEVANT_FIRE_DETECTION", relevantFires, "detections", "NASA_FIRMS", "No relevant fire detection is available, so burning is not strongly attributed.", "REMOTE_SENSING_FIRE");
        }
        if (pm25 >= 90 && co >= properties.getElevatedCo()) score.support(0.35, "PM25_CO_COMBUSTION_PATTERN", pm25, "ug/m3", "CPCB_CAAQMS", "PM2.5 with elevated CO can support combustion evidence.", "POLLUTANT_FINGERPRINT");
        if (landfill > 0) score.support(0.25, "WASTE_SITE_PROXIMITY", landfill, "features", "OPENSTREETMAP_OVERPASS", "Mapped waste-site proximity supports waste-burning screening evidence.", "INFRASTRUCTURE_PROXY");
        if (!fireAvailable) score.capRawScore(0.45);
    }

    private void scoreRegional(Score score, PollutionAttributionInput input) {
        boolean localWeak = number(input.getGeospatial().get("roadDensityKmPerSquareKm")) < properties.getHighRoadDensityKmPerSqKm()
                && number(input.getGeospatial().get("industrialFeatureCount")) == 0
                && number(input.getGeospatial().get("constructionSiteCount")) == 0;
        if (wind(input) >= properties.getStrongWindMps()) score.support(0.32, "STRONG_INCOMING_WIND", wind(input), "m/s", "OPENWEATHER", "Strong wind can support regional transport when local proxies are weak.", "METEOROLOGY");
        if (localWeak && available(input.getGeospatial())) score.support(0.28, "WEAK_LOCAL_SOURCE_PROXIES", true, "", "ATTRIBUTION_ENGINE", "Local source proxies are weak, so regional transport remains plausible.", "COMPARATIVE_CONTEXT");
        if (number(input.getFireEvidence().get("aggregateFireInfluenceScore")) > 0.45) score.support(0.30, "REGIONAL_FIRE_SIGNAL", number(input.getFireEvidence().get("aggregateFireInfluenceScore")), "score", "NASA_FIRMS", "Regional fire influence supports transported pollution.", "REMOTE_SENSING_FIRE");
        if (!available(input.getFireEvidence()) && !available(input.getSatelliteEvidence())) score.capRawScore(0.40);
    }

    private void scoreResidential(Score score, PollutionAttributionInput input) {
        int buildingCount = (int) number(input.getGeospatial().get("buildingFeatureCount"));
        int hour = (int) number(input.getTemporal().get("localHour"));
        double pm25 = number(input.getPollutants().get("pm25"));
        double co = number(input.getPollutants().get("co"));
        if (buildingCount > 80) score.support(0.36, "DENSE_BUILDING_PROXY", buildingCount, "features", "OPENSTREETMAP_OVERPASS", "OSM building density is a neutral residential/urban-density proxy.", "INFRASTRUCTURE_PROXY");
        if ((hour >= 19 || hour <= 6) && pm25 >= 70 && co >= 0.8) score.support(0.34, "EVENING_PM25_CO_PATTERN", hour, "hour", "CPCB_CAAQMS", "Evening/night PM2.5 and CO pattern can support combustion evidence.", "TEMPORAL_POLLUTANT_PATTERN");
        if (pm25 < 40 && has(input.getPollutants(), "pm25")) score.contradict(0.20, "LOW_PM25", pm25, "ug/m3", "CPCB_CAAQMS", "PM2.5 is not elevated enough for a residential-combustion signal.", "POLLUTANT_FINGERPRINT");
    }

    private void scoreOther(Score score, PollutionAttributionInput input) {
        double o3 = number(input.getPollutants().get("o3"));
        if (o3 >= properties.getElevatedO3()) score.support(0.45, "ELEVATED_O3", o3, "ug/m3", "CPCB_CAAQMS", "O3 supports secondary aerosol or photochemical conditions.", "POLLUTANT_FINGERPRINT");
        if (humidity(input) >= 75 && wind(input) > 0 && wind(input) <= properties.getLowWindMps()) score.support(0.28, "HAZE_STAGNATION", humidity(input), "%", "OPENWEATHER", "High humidity with low wind supports secondary haze/stagnation context.", "METEOROLOGY");
        if (score.rawScore == 0 && pollutantCompleteness(input) > 0.4) score.support(0.12, "MIXED_UNEXPLAINED_PATTERN", pollutantCompleteness(input), "fraction", "ATTRIBUTION_ENGINE", "Pollutants are available but no single primary source strongly explains the pattern.", "MIXED_EVIDENCE");
    }

    private List<PollutionSourceContribution> normalize(Map<PollutionSourceType, Score> scores, PollutionAttributionInput input,
                                                        int unknown, Instant timestamp) {
        List<Score> candidates = scores.values().stream()
                .filter(score -> score.rawScore > 0 && score.positive.size() > 0)
                .sorted(Comparator.comparingDouble((Score score) -> score.rawScore).reversed()
                        .thenComparing(score -> score.type.name()))
                .toList();
        int knownBudget = Math.max(0, 100 - unknown);
        double total = candidates.stream().mapToDouble(score -> score.rawScore).sum();
        List<Allocation> allocations = new ArrayList<>();
        int floorTotal = 0;
        for (Score score : candidates) {
            double exact = total > 0 ? (score.rawScore / total) * knownBudget : 0.0;
            int floor = (int) Math.floor(exact);
            floorTotal += floor;
            allocations.add(new Allocation(score, floor, exact - floor));
        }
        int residual = knownBudget - floorTotal;
        allocations.stream()
                .sorted(Comparator.comparingDouble(Allocation::fraction).reversed()
                        .thenComparing(allocation -> allocation.score().type.name()))
                .limit(Math.max(0, residual))
                .forEach(allocation -> allocation.setPercent(allocation.percent() + 1));

        List<PollutionSourceContribution> contributions = new ArrayList<>();
        for (Allocation allocation : allocations) {
            if (allocation.percent() <= 0) continue;
            contributions.add(contribution(allocation.score(), allocation.percent(), input, timestamp));
        }
        if (unknown > 0 || contributions.isEmpty()) {
            contributions.add(unknownContribution(contributions.isEmpty() ? 100 : unknown, timestamp));
        }
        return contributions.stream()
                .sorted(Comparator.comparingInt(PollutionSourceContribution::getContributionPercent).reversed()
                        .thenComparing(item -> item.getSourceType().name()))
                .toList();
    }

    private PollutionSourceContribution contribution(Score score, int percent, PollutionAttributionInput input, Instant timestamp) {
        double confidence = confidence(score, input);
        String limitations = limitations(score, confidence);
        return PollutionSourceContribution.builder()
                .sourceType(score.type)
                .source(score.type.name())
                .displayName(displayName(score.type))
                .contributionPercent(percent)
                .percentage(percent)
                .estimatedContributionPercent(percent)
                .confidence(round(confidence))
                .confidenceLabel(AttributionConfidenceLabel.from(confidence).name())
                .rawScore(round(score.rawScore))
                .positiveEvidenceCount(score.positive.size())
                .contradictingEvidenceCount(score.negative.size())
                .geometrySource(score.primaryGeometrySource())
                .signalType(score.primarySignalType())
                .dataOrigin(SourceScoringRule.DATA_ORIGIN)
                .dataAvailability(score.datasets.isEmpty() ? "LIMITED" : "PARTIAL")
                .dataFreshness(freshness(input))
                .calculationMethod(SourceScoringRule.ENGINE)
                .limitations(limitations)
                .timestamp(timestamp.toString())
                .evidence(score.positive)
                .supportingEvidence(score.positive)
                .contradictingEvidence(score.negative)
                .missingEvidence(score.missing)
                .datasetsUsed(new ArrayList<>(score.datasets))
                .sourceZone(Map.of())
                .build();
    }

    private PollutionSourceContribution unknownContribution(int percent, Instant timestamp) {
        AttributionEvidence evidence = evidence("UNKNOWN_SHARE", percent, "%", "ATTRIBUTION_ENGINE",
                "Unresolved source share retained as UNKNOWN because evidence is incomplete.", "UNCERTAINTY", "supporting", 0.0);
        return PollutionSourceContribution.builder()
                .sourceType(PollutionSourceType.UNKNOWN)
                .source(PollutionSourceType.UNKNOWN.name())
                .displayName(displayName(PollutionSourceType.UNKNOWN))
                .contributionPercent(percent)
                .percentage(percent)
                .estimatedContributionPercent(percent)
                .confidence(LOW_CONFIDENCE)
                .confidenceLabel(AttributionConfidenceLabel.from(LOW_CONFIDENCE).name())
                .rawScore(0.0)
                .positiveEvidenceCount(1)
                .contradictingEvidenceCount(0)
                .geometrySource("none")
                .signalType("UNCERTAINTY")
                .dataOrigin("UNAVAILABLE")
                .dataAvailability("INSUFFICIENT")
                .dataFreshness("UNKNOWN")
                .calculationMethod(SourceScoringRule.ENGINE)
                .limitations("Insufficient independent evidence; percentage is intentionally preserved as UNKNOWN.")
                .timestamp(timestamp.toString())
                .evidence(List.of(evidence))
                .supportingEvidence(List.of(evidence))
                .contradictingEvidence(List.of())
                .missingEvidence(List.of("UNRESOLVED_EVIDENCE_GAPS"))
                .datasetsUsed(List.of("attribution_engine"))
                .sourceZone(Map.of())
                .build();
    }

    private int unknownShare(Map<PollutionSourceType, Score> scores, Map<String, Object> coverage) {
        double available = coverage.values().stream().filter(Boolean.TRUE::equals).count() / Math.max(1.0, coverage.size());
        int positiveSignals = scores.values().stream().mapToInt(score -> score.positive.size()).sum();
        long independentDatasets = scores.values().stream().flatMap(score -> score.datasets.stream()).collect(java.util.stream.Collectors.toSet()).size();
        double strength = scores.values().stream().mapToDouble(score -> Math.max(0.0, score.rawScore)).sum();
        double known = 0.18 + available * 0.34 + Math.min(0.22, positiveSignals * 0.025) + Math.min(0.16, independentDatasets * 0.035) + Math.min(0.10, strength * 0.035);
        boolean weak = positiveSignals < 3 || independentDatasets < 2 || strength < 0.70;
        if (weak) known = Math.min(known, 0.55);
        int unknown = (int) Math.round((1.0 - clamp(known, 0.10, 0.88)) * 100.0);
        Score traffic = scores.get(PollutionSourceType.TRAFFIC);
        if (traffic != null && traffic.rawScore > 0 && !traffic.hasSignalType("POLLUTANT_FINGERPRINT")) {
            unknown = Math.max(unknown, properties.getProxyOnlyUnknownFloorPercent());
        }
        long missingCritical = coverage.values().stream().filter(value -> !Boolean.TRUE.equals(value)).count();
        unknown += (int) missingCritical * properties.getMissingCriticalEvidenceUnknownBoostPercent();
        return Math.max(12, Math.min(100, unknown));
    }

    private double confidence(Score score, PollutionAttributionInput input) {
        double coverage = evidenceCoverage(input).values().stream().filter(Boolean.TRUE::equals).count() / 6.0;
        double support = Math.min(1.0, score.positive.size() / 4.0);
        double diversity = Math.min(1.0, score.datasets.size() / 3.0);
        double contradiction = Math.min(0.4, score.negative.size() * 0.08);
        double confidence = 0.10 + coverage * 0.22 + support * 0.26 + diversity * 0.24 + pollutantCompleteness(input) * 0.18 - contradiction;
        if (score.positive.size() < 2 || score.datasets.size() < 2) confidence = Math.min(confidence, 0.49);
        if (score.type == PollutionSourceType.INDUSTRIAL && number(input.getGeospatial().get("industrialFeatureCount")) <= 0) confidence = Math.min(confidence, 0.24);
        if (score.type == PollutionSourceType.BIOMASS_WASTE_BURNING && number(input.getFireEvidence().get("relevantDetectionCount")) <= 0) confidence = Math.min(confidence, 0.34);
        return clamp(confidence, 0.05, 0.92);
    }

    private AttributionResult unavailable(PollutionAttributionInput input, Instant generatedAt, String warning) {
        Map<String, Object> coverage = evidenceCoverage(input);
        return AttributionResult.builder()
                .status("UNAVAILABLE")
                .city(string(input.getLocation().get("city")))
                .cityId(string(input.getLocation().get("city")))
                .location(input.getLocation())
                .currentAqi(input.getCurrentAqi())
                .method(method())
                .timestamp(generatedAt)
                .generatedAt(generatedAt)
                .snapshotId(input.getSnapshotId())
                .locationKey(input.getLocationKey())
                .snapshotObservedAt(input.getSnapshotObservedAt())
                .snapshotGeneratedAt(input.getSnapshotGeneratedAt())
                .snapshotReused(input.getSnapshotReused())
                .locationHash(input.getLocationHash())
                .sources(List.of())
                .dominantSource(PollutionSourceType.UNKNOWN)
                .overallConfidence(0.0)
                .overallConfidenceLabel(AttributionConfidenceLabel.INSUFFICIENT_EVIDENCE.name())
                .unknownContributionPercent(100)
                .explanation("Current AQI is unavailable; source percentages are not generated.")
                .calculationMethod(SourceScoringRule.ENGINE)
                .rawScores(Map.of())
                .evidenceCoverage(coverage)
                .diagnostics(Map.of("normalizationAfterRounding", Map.of("UNKNOWN", 100)))
                .providerStatus(providerStatus(input))
                .warnings(List.of(warning))
                .build();
    }

    private Map<String, Object> diagnostics(PollutionAttributionInput input, Map<PollutionSourceType, Score> scores,
                                            int unknown, List<PollutionSourceContribution> contributions) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("pollutantsUsed", input.getPollutants());
        diagnostics.put("weatherUsed", input.getWeather());
        diagnostics.put("historicalContext", input.getHistoricalContext());
        diagnostics.put("geospatialEvidence", input.getGeospatial());
        diagnostics.put("fireEvidence", input.getFireEvidence());
        diagnostics.put("satelliteEvidence", input.getSatelliteEvidence());
        diagnostics.put("sourceSpecificRawScores", rawScores(scores));
        diagnostics.put("positiveEvidence", scores.values().stream().collect(java.util.stream.Collectors.toMap(
                score -> score.type.name(), score -> score.positive, (a, b) -> a, LinkedHashMap::new)));
        diagnostics.put("contradictingEvidence", scores.values().stream().collect(java.util.stream.Collectors.toMap(
                score -> score.type.name(), score -> score.negative, (a, b) -> a, LinkedHashMap::new)));
        diagnostics.put("missingEvidence", scores.values().stream().collect(java.util.stream.Collectors.toMap(
                score -> score.type.name(), score -> score.missing, (a, b) -> a, LinkedHashMap::new)));
        diagnostics.put("sourceDebugDetails", sourceDebugDetails(scores, contributions));
        diagnostics.put("unknownContributionCalculation", Map.of("unknownContributionPercent", unknown));
        diagnostics.put("normalizationAfterRounding", contributions.stream().collect(java.util.stream.Collectors.toMap(
                item -> item.getSourceType().name(), PollutionSourceContribution::getContributionPercent, Integer::sum, LinkedHashMap::new)));
        diagnostics.put("providerTimestamps", Map.of(
                "aqi", input.getCurrentAqi().getOrDefault("observedAt", ""),
                "weather", input.getWeather().getOrDefault("observedAt", ""),
                "geospatial", input.getGeospatial().getOrDefault("observedAt", ""),
                "fire", input.getFireEvidence().getOrDefault("observedAt", ""),
                "satellite", input.getSatelliteEvidence().getOrDefault("observedAt", "")
        ));
        return diagnostics;
    }

    private List<Map<String, Object>> sourceDebugDetails(Map<PollutionSourceType, Score> scores,
                                                         List<PollutionSourceContribution> contributions) {
        Map<PollutionSourceType, Integer> normalized = contributions.stream()
                .collect(java.util.stream.Collectors.toMap(PollutionSourceContribution::getSourceType,
                        PollutionSourceContribution::getContributionPercent, Integer::sum));
        return scores.values().stream()
                .sorted(Comparator.comparing(score -> score.type.name()))
                .map(score -> {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("source", score.type.name());
                    detail.put("rawScore", round(score.rawScore));
                    detail.put("positiveScore", round(score.positiveScore));
                    detail.put("contradictionPenalty", round(score.contradictionPenalty));
                    detail.put("availabilityFactor", score.positive.isEmpty() ? 0.0 : 1.0);
                    detail.put("freshnessFactor", 1.0);
                    detail.put("finalScore", round(score.rawScore));
                    detail.put("normalizedContribution", normalized.getOrDefault(score.type, 0));
                    detail.put("supportingEvidence", score.positive);
                    detail.put("contradictingEvidence", score.negative);
                    detail.put("missingEvidence", score.missing);
                    return detail;
                })
                .toList();
    }

    private Map<String, Object> evidenceCoverage(PollutionAttributionInput input) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("pollutantsAvailable", pollutantCompleteness(input) > 0);
        coverage.put("weatherAvailable", has(input.getWeather(), "humidityPercent") || has(input.getWeather(), "windSpeedMps"));
        coverage.put("geospatialAvailable", available(input.getGeospatial()));
        coverage.put("fireAvailable", available(input.getFireEvidence()));
        coverage.put("satelliteAvailable", available(input.getSatelliteEvidence()));
        coverage.put("historicalContextAvailable", Boolean.TRUE.equals(input.getHistoricalContext().get("available")));
        return coverage;
    }

    private List<String> warnings(PollutionAttributionInput input, Map<String, Object> coverage) {
        List<String> warnings = new ArrayList<>();
        if (!Boolean.TRUE.equals(coverage.get("geospatialAvailable"))) warnings.add("GEOSPATIAL_EVIDENCE_UNAVAILABLE");
        if (!Boolean.TRUE.equals(coverage.get("fireAvailable"))) warnings.add("FIRE_EVIDENCE_UNAVAILABLE");
        if (!Boolean.TRUE.equals(coverage.get("satelliteAvailable"))) warnings.add("SATELLITE_EVIDENCE_UNAVAILABLE");
        if (!Boolean.TRUE.equals(coverage.get("historicalContextAvailable"))) warnings.add("HISTORICAL_CONTEXT_INSUFFICIENT");
        warnings.add("SOURCE_CONTRIBUTIONS_ARE_ESTIMATES_NOT_MEASURED_SOURCE_APPORTIONMENT");
        return warnings;
    }

    private Map<String, Object> method() {
        return Map.of(
                "engine", SourceScoringRule.ENGINE,
                "version", properties.getEngineVersion(),
                "isMeasuredSourceApportionment", false
        );
    }

    private Map<String, String> providerStatus(PollutionAttributionInput input) {
        Map<String, String> status = new LinkedHashMap<>();
        input.getProviderStatus().forEach((key, value) -> status.put(String.valueOf(key), String.valueOf(value)));
        return status;
    }

    private Map<String, Double> rawScores(Map<PollutionSourceType, Score> scores) {
        Map<String, Double> raw = new LinkedHashMap<>();
        scores.values().stream()
                .sorted(Comparator.comparing(score -> score.type.name()))
                .forEach(score -> raw.put(score.type.name(), round(score.rawScore)));
        return raw;
    }

    private double weightedConfidence(List<PollutionSourceContribution> contributions) {
        return contributions.stream().mapToDouble(item -> item.getConfidence() * item.getContributionPercent() / 100.0).sum();
    }

    private String limitations(Score score, double confidence) {
        List<String> limitations = new ArrayList<>();
        if (score.positive.size() < 2 || score.datasets.size() < 2) limitations.add("Single-proxy or limited independent evidence; confidence is capped.");
        if (confidence < 0.35) limitations.add("Low-confidence attribution; treat as screening evidence only.");
        if (score.type == PollutionSourceType.TRAFFIC) limitations.add("OSM road density is a traffic infrastructure proxy, not real-time traffic.");
        if (score.type == PollutionSourceType.INDUSTRIAL) limitations.add("Industrial attribution requires real geometry and marker pollutants; unavailable geometry is not inferred.");
        return limitations.isEmpty() ? "Estimated evidence-fusion contribution, not measured source apportionment." : String.join(" ", limitations);
    }

    private String displayName(PollutionSourceType source) {
        return switch (source) {
            case TRAFFIC -> "Traffic emissions";
            case ROAD_DUST_CONSTRUCTION -> "Road dust / construction";
            case INDUSTRIAL -> "Industrial sources";
            case BIOMASS_WASTE_BURNING -> "Biomass / waste burning";
            case REGIONAL_TRANSPORT -> "Regional transport";
            case RESIDENTIAL_COMBUSTION -> "Residential combustion";
            case SECONDARY_AEROSOL_OR_OTHER -> "Secondary aerosol / other";
            case UNKNOWN -> "Unknown / insufficiently explained";
        };
    }

    private boolean hasCurrentAqi(PollutionAttributionInput input) {
        return number(input.getCurrentAqi().get("value")) > 0 || number(input.getCurrentAqi().get("currentAqi")) > 0;
    }

    private boolean available(Map<String, Object> map) {
        return Boolean.TRUE.equals(map.get("available"));
    }

    private boolean has(Map<String, Object> map, String key) {
        return map.containsKey(key) && map.get(key) != null;
    }

    private double pollutantCompleteness(PollutionAttributionInput input) {
        return List.of("pm25", "pm10", "no2", "so2", "co", "o3").stream().filter(key -> has(input.getPollutants(), key)).count() / 6.0;
    }

    private double wind(PollutionAttributionInput input) {
        return number(input.getWeather().get("windSpeedMps"));
    }

    private double humidity(PollutionAttributionInput input) {
        return number(input.getWeather().get("humidityPercent"));
    }

    private double rain(PollutionAttributionInput input) {
        return number(input.getWeather().get("rainfallMm"));
    }

    private String freshness(PollutionAttributionInput input) {
        Object observedAt = input.getCurrentAqi().get("observedAt");
        if (observedAt == null) return "UNKNOWN";
        return "PROVIDER_TIMESTAMP_REPORTED";
    }

    private AttributionEvidence evidence(String type, Object value, String unit, String provider, String message,
                                         String signalType, String direction, double weight) {
        return AttributionEvidence.builder()
                .type(type)
                .message(message)
                .dataset(provider)
                .provider(provider)
                .signal(type)
                .value(value)
                .unit(unit)
                .weight(round(weight))
                .signalType(signalType)
                .geometrySource(signalType != null && signalType.contains("GEOMETRY") ? provider : "none")
                .direction(direction)
                .dataOrigin(SourceScoringRule.DATA_ORIGIN)
                .timestamp(Instant.now().toString())
                .build();
    }

    private Object value(Map<String, Object> map, String key) {
        return map.get(key);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private class Score {
        private final PollutionSourceType type;
        private final Instant timestamp;
        private double rawScore;
        private double positiveScore;
        private double contradictionPenalty;
        private final List<AttributionEvidence> positive = new ArrayList<>();
        private final List<AttributionEvidence> negative = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private final Set<String> datasets = new LinkedHashSet<>();

        private Score(PollutionSourceType type, Instant timestamp) {
            this.type = type;
            this.timestamp = timestamp;
        }

        private void support(double weight, String signal, Object value, String unit, String provider, String message, String signalType) {
            rawScore += weight;
            positiveScore += weight;
            datasets.add(provider.toLowerCase(Locale.ROOT));
            positive.add(evidence(signal, value, unit, provider, message, signalType, "supporting", weight));
        }

        private void contradict(double weight, String signal, Object value, String unit, String provider, String message, String signalType) {
            rawScore = Math.max(0.0, rawScore - weight);
            contradictionPenalty += weight;
            datasets.add(provider.toLowerCase(Locale.ROOT));
            negative.add(evidence(signal, value, unit, provider, message, signalType, "contradicting", -weight));
        }

        private void missing(String reason) {
            if (reason != null && !reason.isBlank()) missing.add(reason);
        }

        private void capRawScore(double max) {
            rawScore = Math.min(rawScore, max);
        }

        private String primarySignalType() {
            return positive.isEmpty() ? "UNKNOWN" : positive.get(0).getSignalType();
        }

        private String primaryGeometrySource() {
            return positive.isEmpty() ? "none" : positive.get(0).getGeometrySource();
        }

        private boolean hasSignalType(String signalType) {
            return positive.stream().anyMatch(item -> signalType.equals(item.getSignalType()));
        }
    }

    private static class Allocation {
        private final Score score;
        private final double fraction;
        private int percent;

        private Allocation(Score score, int percent, double fraction) {
            this.score = score;
            this.fraction = fraction;
            this.percent = percent;
        }

        private Score score() {
            return score;
        }

        private double fraction() {
            return fraction;
        }

        private int percent() {
            return percent;
        }

        private void setPercent(int percent) {
            this.percent = percent;
        }
    }
}
