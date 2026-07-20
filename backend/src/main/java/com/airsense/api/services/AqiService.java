package com.airsense.api.services;

import com.airsense.api.dto.*;
import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.SensorData;
import com.airsense.api.entities.Advisory;
import com.airsense.api.repositories.PredictionRepository;
import com.airsense.api.repositories.GridForecastRepository;
import com.airsense.api.repositories.SensorDataRepository;
import com.airsense.api.repositories.AdvisoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AqiService {
    @Autowired
    private SensorDataRepository sensorDataRepository;

    @Autowired
    private PredictionRepository predictionRepository;

    @Autowired
    private GridForecastRepository gridForecastRepository;

    @Autowired
    private AdvisoryRepository advisoryRepository;

    public CityAqiSummaryDto getCitySummary() {
        return getCitySummary("UNKNOWN_PLACE");
    }

    public CityAqiSummaryDto getCitySummary(String cityId) {
        List<SensorData> recentData = getLatestDataPerStation(cityId);

        if (recentData.isEmpty()) {
            return CityAqiSummaryDto.builder()
                    .city(cityNameFor(cityId))
                    .aqi(null)
                    .category("UNAVAILABLE")
                    .dominantPollutant("N/A")
                    .updatedAt(Instant.now().toString())
                    .build();
        }

        // Calculate average AQI
        int avgAqi = (int) recentData.stream()
                .filter(d -> d.getPollutants() != null && d.getPollutants().getAqi() != null)
                .mapToInt(d -> d.getPollutants().getAqi())
                .average()
                .orElse(0);

        String category = determineCategory(avgAqi);

        return CityAqiSummaryDto.builder()
                .city(recentData.get(0).getCityName() != null ? recentData.get(0).getCityName() : cityNameFor(cityId))
                .aqi(avgAqi)
                .category(category)
                .dominantPollutant(dominantPollutant(recentData))
                .updatedAt(recentData.get(0).getTimestamp().toString())
                .build();
    }

    public List<NeighborhoodDto> getNeighborhoods() {
        return getNeighborhoods("UNKNOWN_PLACE");
    }

    public List<NeighborhoodDto> getNeighborhoods(String cityId) {
        List<SensorData> recentData = getLatestDataPerStation(cityId);

        if (recentData.isEmpty()) {
            return List.of();
        }

        return recentData.stream().map(data -> {
            Integer aqi = data.getPollutants() != null ? data.getPollutants().getAqi() : null;
            return new NeighborhoodDto(
                    data.getWardId() != null ? data.getWardId() : data.getSensorId(),
                    data.getStationName() != null ? data.getStationName() : "Unknown",
                    aqi,
                    aqi != null && aqi > 0 ? determineCategory(aqi) : "UNAVAILABLE",
                    "PM2.5"
            );
        }).collect(Collectors.toList());
    }

    private List<SensorData> getLatestDataPerStation() {
        return getLatestDataPerStation(null);
    }

    private List<SensorData> getLatestDataPerStation(String cityId) {
        // Simple approach: get last 24h of data, then group by station and get the max timestamp
        Instant yesterday = Instant.now().minus(24, ChronoUnit.HOURS);
        List<SensorData> recent = sensorDataRepository.findByTimestampBetween(yesterday, Instant.now());
        if (cityId != null && !cityId.isBlank()) {
            recent = recent.stream()
                    .filter(data -> cityId.equalsIgnoreCase(data.getCityId()))
                    .collect(Collectors.toList());
        }

        // Group by sensorId and find the one with the latest timestamp
        return recent.stream()
                .collect(Collectors.groupingBy(SensorData::getSensorId,
                        Collectors.maxBy(Comparator.comparing(SensorData::getTimestamp))))
                .values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    private String determineCategory(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Satisfactory";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "Poor";
        if (aqi <= 400) return "Very Poor";
        return "Severe";
    }

    private String cityNameFor(String cityId) {
        return cityId == null || cityId.isBlank() || "UNKNOWN_PLACE".equalsIgnoreCase(cityId)
                ? "Unavailable"
                : cityId;
    }

    private String dominantPollutant(List<SensorData> data) {
        double pm25 = data.stream()
                .filter(item -> item.getPollutants() != null && item.getPollutants().getPm25() != null)
                .mapToDouble(item -> item.getPollutants().getPm25())
                .average()
                .orElse(0.0);
        double pm10 = data.stream()
                .filter(item -> item.getPollutants() != null && item.getPollutants().getPm10() != null)
                .mapToDouble(item -> item.getPollutants().getPm10())
                .average()
                .orElse(0.0);
        double no2 = data.stream()
                .filter(item -> item.getPollutants() != null && item.getPollutants().getNo2() != null)
                .mapToDouble(item -> item.getPollutants().getNo2())
                .average()
                .orElse(0.0);
        double so2 = data.stream()
                .filter(item -> item.getPollutants() != null && item.getPollutants().getSo2() != null)
                .mapToDouble(item -> item.getPollutants().getSo2())
                .average()
                .orElse(0.0);
        double o3 = data.stream()
                .filter(item -> item.getPollutants() != null && item.getPollutants().getO3() != null)
                .mapToDouble(item -> item.getPollutants().getO3())
                .average()
                .orElse(0.0);
        Map<String, Double> averages = Map.of("PM2.5", pm25, "PM10", pm10, "NO2", no2, "SO2", so2, "O3", o3);
        return averages.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() > 0.0)
                .map(Map.Entry::getKey)
                .orElse("N/A");
    }

    // -----------------------------------------------------------------------
    // City-level forecast (aggregates across all wards)
    // -----------------------------------------------------------------------


        public List<ForecastDayDto> getForecast() {
        // Retrieve all predictions
        List<Prediction> allPreds = predictionRepository.findAll();

        if (allPreds.isEmpty()) {
            return unavailableForecastDays("Forecast unavailable until a genuinely trained and evaluated model exists.");
        }

        // Find the latest prediction
        Prediction latest = allPreds.stream()
            .max(Comparator.comparing(Prediction::getGeneratedAt))
            .orElse(null);

        // Guard against missing hourly data
        if (latest == null || latest.getPredictions() == null || latest.getPredictions().isEmpty()) {
            return unavailableForecastDays("No trained hourly forecast data is available.");
        }

        return aggregateToDailyForecasts(latest);
    }

    // -----------------------------------------------------------------------
    // Per-ward forecast (reads from precomputed Predictions collection)
    // -----------------------------------------------------------------------

    /**
     * Get the full 72h forecast for a specific ward from the precomputed
     * Predictions collection. Returns both the raw hourly array and a
     * 3-day daily summary with source attribution.
     */
    public WardForecastResponseDto getForecastByWard(String wardId) {
        Optional<Prediction> predOpt = predictionRepository.findTopByWardIdOrderByGeneratedAtDesc(wardId);

        if (predOpt.isEmpty()) {
            return WardForecastResponseDto.builder()
                    .wardId(wardId)
                    .generatedAt(Instant.now().toString())
                    .horizonHours(72)
                    .status("UNAVAILABLE")
                    .mode("UNAVAILABLE")
                    .reason("Forecast unavailable until a genuinely trained and evaluated model exists.")
                    .hourlyPredictions(List.of())
                    .dailySummary(unavailableForecastDays("No trained forecast is available for this ward."))
                    .sourceAttribution(Map.of())
                    .build();
        }

        Prediction prediction = predOpt.get();

        // Map hourly predictions to DTO
        List<HourlyForecastDto> hourlyDtos = prediction.getPredictions().stream()
                .map(hp -> HourlyForecastDto.builder()
                        .timestamp(hp.getTimestamp())
                        .predictedAqi(hp.getPredictedAqi())
                        .predictedPm25(hp.getPredictedPm25())
                        .category(hp.getCategory())
                        .build())
                .collect(Collectors.toList());

        // Daily aggregation
        List<ForecastDayDto> dailySummary = aggregateToDailyForecasts(prediction);

        // Source attribution from feature importance
        Map<String, Double> attribution = extractSourceAttribution(prediction);

        return WardForecastResponseDto.builder()
                .wardId(prediction.getWardId())
                .sensorId(prediction.getSensorId())
                .generatedAt(prediction.getGeneratedAt())
                .horizonHours(prediction.getHorizonHours())
                .status("AVAILABLE")
                .mode(valueOrDefault(prediction.getModelVersion(), "TRAINED_MODEL"))
                .hourlyPredictions(hourlyDtos)
                .dailySummary(dailySummary)
                .sourceAttribution(attribution)
                .build();
    }

    public Object getForecastByGrid(String gridId) {
        var opt = gridForecastRepository.findFirstByGridIdOrderByGeneratedAtDesc(gridId);
        if (opt.isEmpty()) {
            return Map.of("error", "No grid forecast found for " + gridId);
        }
        var grid = opt.get();
        return Map.of(
            "gridId", grid.getGridId(),
            "wardId", grid.getWardId() != null ? grid.getWardId() : "",
            "location", grid.getLocation() != null ? grid.getLocation() : new double[0],
            "generatedAt", grid.getGeneratedAt(),
            "hourlyPredictions", grid.getPredictions()
        );
    }

    // -----------------------------------------------------------------------
    // Source Attribution
    // -----------------------------------------------------------------------

    /**
     * Get source attribution for a specific ward.
     * Maps XGBoost feature importance to human-readable pollution source names.
     */
    public List<SourceAttributionDto> getSourceAttribution(String wardId) {
        Optional<Prediction> predOpt = predictionRepository.findTopByWardIdOrderByGeneratedAtDesc(wardId);

        if (predOpt.isEmpty()) {
            return List.of();
        }

        Map<String, Double> attribution = extractSourceAttribution(predOpt.get());

        return attribution.entrySet().stream()
                .map(e -> SourceAttributionDto.builder()
                        .source(e.getKey())
                        .percentage(Math.round(e.getValue() * 10000.0) / 100.0) // convert to percentage
                        .build())
                .sorted((a, b) -> Double.compare(b.getPercentage(), a.getPercentage()))
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Health Advisory
    // -----------------------------------------------------------------------

    public HealthAdvisoryDto getAdvisory() {
        return new HealthAdvisoryDto(
                "orange",
                "Limit prolonged outdoor exertion",
                "Due to consistently elevated PM2.5 levels in the region, vulnerable individuals should take precautions.",
                Arrays.asList(
                        "Wear an N95 mask if outdoors for over an hour",
                        "Keep windows closed during early morning peak traffic",
                        "Use air purifiers if available indoors"
                ),
                new VulnerableAdvisoryDto(
                        "High Risk for Sensitive Groups",
                        "People with asthma, COPD, and cardiovascular conditions may experience aggravated symptoms.",
                        Arrays.asList(
                                "Keep inhalers readily accessible",
                                "Avoid all outdoor physical activity",
                                "Seek medical advice if symptoms worsen"
                        )
                )
        );
    }

    public Optional<HealthAdvisoryDto> getAdvisoryByWard(String wardId) {
        return advisoryRepository.findTopByWardIdOrderByGeneratedAtDesc(wardId).map(advisory -> {
            return new HealthAdvisoryDto(
                    "orange",
                    "Ward-Level Health Advisory",
                    advisory.getCitizenAdvisory() != null ? advisory.getCitizenAdvisory() : "No specific advisory generated.",
                    Arrays.asList(
                            "Follow general guidelines for current AQI: " + advisory.getPeakAqi(),
                            "Primary identified source: " + advisory.getPrimarySource()
                    ),
                    new VulnerableAdvisoryDto(
                            "Precautions for Sensitive Groups",
                            "Individuals with respiratory conditions should monitor their health closely.",
                            Arrays.asList("Minimize outdoor activities", "Ensure indoor ventilation is managed")
                    )
            );
        });
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Aggregate a Prediction's 72 hourly entries into 3-day summaries.
     */
    private List<ForecastDayDto> aggregateToDailyForecasts(Prediction prediction) {
        List<Prediction.HourlyPrediction> hourly = prediction.getPredictions();
        List<ForecastDayDto> dailyForecasts = new ArrayList<>();

        for (int day = 0; day < 3; day++) {
            int startIdx = day * 24;
            int endIdx = Math.min(startIdx + 24, hourly.size());

            if (startIdx >= hourly.size()) break;

            List<Prediction.HourlyPrediction> dayData = hourly.subList(startIdx, endIdx);

            List<Integer> validAqi = dayData.stream()
                    .map(Prediction.HourlyPrediction::getPredictedAqi)
                    .filter(value -> value != null && value > 0)
                    .toList();
            if (validAqi.isEmpty()) {
                dailyForecasts.add(unavailableForecastDay(day + 1, "Trained forecast did not return a valid AQI for this day."));
                continue;
            }
            int maxAqi = validAqi.stream().mapToInt(Integer::intValue).max().orElseThrow();
            int avgAqi = (int) validAqi.stream().mapToInt(Integer::intValue).average().orElse(maxAqi);
            double avgPm25 = dayData.stream()
                    .mapToDouble(Prediction.HourlyPrediction::getPredictedPm25)
                    .average().orElse(0.0);
            String category = determineCategory(maxAqi);

            String recommendation = maxAqi > 200
                    ? "Air quality may cause discomfort. Limit outdoor exertion."
                    : "Conditions are generally acceptable.";

            String dateStr = todayPlusDays(day + 1);

            dailyForecasts.add(new ForecastDayDto(
                    dateStr, maxAqi, category, (int) avgPm25, (int) (avgPm25 * 1.5), recommendation));
        }

        // Pad to 3 if incomplete
        while (dailyForecasts.size() < 3) {
            dailyForecasts.add(unavailableForecastDay(dailyForecasts.size() + 1, "Forecast horizon unavailable."));
        }

        return dailyForecasts;
    }

    private List<ForecastDayDto> unavailableForecastDays(String reason) {
        return List.of(
                unavailableForecastDay(1, reason),
                unavailableForecastDay(2, reason),
                unavailableForecastDay(3, reason)
        );
    }

    private ForecastDayDto unavailableForecastDay(int days, String reason) {
        return new ForecastDayDto(todayPlusDays(days), null, "UNAVAILABLE", null, null, reason);
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * Extract source attribution from a prediction's featureImportanceSummary.
     * The AI service returns a map of human-readable labels → normalised importance.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> extractSourceAttribution(Prediction prediction) {
        Object raw = prediction.getFeatureImportanceSummary();
        if (raw == null) {
            return Map.of();
        }

        try {
            if (raw instanceof Map) {
                Map<String, Object> rawMap = (Map<String, Object>) raw;
                Map<String, Double> result = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                    double value = entry.getValue() instanceof Number
                            ? ((Number) entry.getValue()).doubleValue()
                            : 0.0;
                    result.put(entry.getKey(), value);
                }
                return result;
            }
        } catch (Exception e) {
            // Defensive: if the shape is unexpected, return empty
        }
        return Map.of();
    }

    private String todayPlusDays(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).toString().substring(0, 10);
    }
}
