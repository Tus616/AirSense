package com.airsense.api.forecast;

import com.airsense.api.attribution.PollutionSourceType;
import com.airsense.api.entities.SensorData;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.repositories.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class FeatureBuilder {
    private static final int EXPECTED_DATASET_COUNT = 10;

    @Autowired(required = false)
    private SensorDataRepository sensorDataRepository;

    public ForecastFeatures build(CityEnvironmentalContext context, ForecastRequest request, PollutionSourceType dominantSource) {
        CityEnvironmentalContext safeContext = context != null ? context : CityEnvironmentalContext.empty(null);
        ForecastRequest safeRequest = (request != null ? request : ForecastRequest.builder().build()).normalized();
        Map<String, Object> firstStation = firstStation(safeContext);
        Map<String, Object> pollutants = pollutants(safeContext, firstStation);
        Map<String, Object> aqi = safeMap(safeContext.getAqi());
        List<String> datasets = availableDatasets(safeContext);
        Instant timestamp = safeContext.getTimestamp() != null ? safeContext.getTimestamp() : Instant.now();
        ZonedDateTime dateTime = timestamp.atZone(ZoneOffset.UTC);
        String cityId = valueOrDefault(safeContext.getCityId(), safeRequest.getCityId());
        SensorData latestStored = latestStoredSnapshot(cityId);
        double currentAqi = firstPositive(number(aqi.get("currentAqi")), number(pollutants.get("aqi")), storedAqi(latestStored));
        int historyCount = historicalAqiCount(safeContext, cityId);
        double historicalAverage = historicalAqiAverage(safeContext, cityId);
        String stationName = valueOrDefault(string(first(firstStation, "stationName", "station", "name")), string(aqi.get("stationName")));
        double stationLatitude = firstPositive(number(firstStation.get("latitude")), number(aqi.get("stationLatitude")), safeRequest.getLatitude() != null ? safeRequest.getLatitude() : 0.0);
        double stationLongitude = firstPositive(number(firstStation.get("longitude")), number(aqi.get("stationLongitude")), safeRequest.getLongitude() != null ? safeRequest.getLongitude() : 0.0);

        ForecastFeatures features = ForecastFeatures.builder()
                .city(valueOrDefault(safeContext.getCity(), safeContext.getCityId()))
                .cityId(cityId)
                .wardId(valueOrDefault(safeRequest.getWardId(), valueOrDefault(string(firstStation.get("wardId")), valueOrDefault(string(firstStation.get("stationName")), safeRequest.getCityId()))))
                .sensorId(buildSensorId(cityId, stationName, stationLatitude, stationLongitude,
                        valueOrDefault(string(firstStation.get("sensorId")),
                                valueOrDefault(string(firstStation.get("stationId")),
                                        valueOrDefault(safeRequest.getPlaceId(), valueOrDefault(safeRequest.getCityId(), "UNKNOWN_SENSOR"))))))
                .contextTimestamp(timestamp)
                .currentAqi(currentAqi)
                .historyCount(historyCount)
                .historicalAqiAverage(historicalAverage)
                .historicalTrend(historicalAverage > 0 ? currentAqi - historicalAverage : 0.0)
                .pm25(number(first(pollutants, "pm25", "pm2_5", "pm2.5")))
                .pm10(number(pollutants.get("pm10")))
                .no2(number(pollutants.get("no2")))
                .so2(number(pollutants.get("so2")))
                .co(number(pollutants.get("co")))
                .temperature(firstPositive(safeContext.getTemperature(), number(safeMap(safeContext.getWeather()).get("temperature"))))
                .humidity(firstPositive(safeContext.getHumidity(), number(safeMap(safeContext.getWeather()).get("humidity"))))
                .pressure(firstPositive(number(safeMap(safeContext.getWeather()).get("pressure")), forecastAverage(safeContext, "pressure")))
                .windSpeed(firstPositive(number(safeMap(safeContext.getWind()).get("speed")), number(safeMap(safeContext.getWeather()).get("windSpeed"))))
                .windDirection(firstPositive(number(safeMap(safeContext.getWind()).get("direction")), number(safeMap(safeContext.getWeather()).get("windDirection"))))
                .rainfall(firstPositive(number(safeMap(safeContext.getWeather()).get("rainfall")), forecastAverage(safeContext, "rainfall")))
                .rainProbability(rainProbability(safeContext))
                .satelliteThermalAnomaly(thermalAnomaly(safeContext))
                .trafficCongestion(number(safeMap(safeContext.getTraffic()).get("averageCongestionIndex")))
                .trafficSpeed(number(safeMap(safeContext.getTraffic()).get("averageSpeed")))
                .constructionCount(safeList(safeContext.getConstruction()).size())
                .highDustConstructionCount(highDustConstructionCount(safeContext))
                .industrialCount(safeList(safeContext.getIndustries()).size())
                .highRiskIndustrialCount(highRiskIndustrialCount(safeContext))
                .greenCoverIndex(number(safeMap(safeContext.getGreenCover()).get("greenCoverIndex")))
                .populationDensity(populationDensity(safeContext))
                .season(season(dateTime.getMonthValue()))
                .hourOfDay(dateTime.getHour())
                .dayOfWeek(dateTime.getDayOfWeek())
                .inputCompleteness(Math.min(1.0, (double) datasets.size() / EXPECTED_DATASET_COUNT))
                .providerConfidence(providerConfidence(safeContext))
                .dataFreshness(dataFreshness(timestamp))
                .attributedDominantSource(dominantSource != null ? dominantSource : PollutionSourceType.UNKNOWN)
                .availableDatasets(datasets)
                .build();

        log.info("Forecast FeatureBuilder Success cityId={} datasets={} completeness={}",
                features.getCityId(), datasets.size(), features.getInputCompleteness());
        return features;
    }

    private List<String> availableDatasets(CityEnvironmentalContext context) {
        List<String> datasets = new ArrayList<>();
        if (hasAqi(context)) datasets.add("aqi");
        if (!safeList(context.getHistoricalAQI()).isEmpty() || hasStoredHistory(context.getCityId())) datasets.add("historicalAQI");
        if (!safeMap(context.getWeather()).isEmpty() || !safeMap(context.getWind()).isEmpty()) datasets.add("weather");
        if (!safeListObject(safeMap(context.getWeather()).get("hourlyForecast")).isEmpty()) datasets.add("openWeatherHourlyForecast");
        if (!safeMap(context.getSatellite()).isEmpty()) datasets.add("satellite");
        if (!safeMap(context.getTraffic()).isEmpty()) datasets.add("traffic");
        if (!safeList(context.getConstruction()).isEmpty()) datasets.add("construction");
        if (!safeList(context.getIndustries()).isEmpty()) datasets.add("industries");
        if (!safeMap(context.getGreenCover()).isEmpty()) datasets.add("greenCover");
        if (!safeMap(context.getPopulation()).isEmpty()) datasets.add("population");
        if (!safeMap(context.getLandUse()).isEmpty()) datasets.add("landUse");
        return datasets;
    }

    private boolean hasAqi(CityEnvironmentalContext context) {
        Map<String, Object> aqi = safeMap(context.getAqi());
        return number(aqi.get("currentAqi")) > 0 || !safeListObject(aqi.get("stations")).isEmpty();
    }

    private double historicalAqiAverage(CityEnvironmentalContext context, String cityId) {
        List<Map<String, Object>> history = !safeList(context.getHistoricalAQI()).isEmpty()
                ? safeList(context.getHistoricalAQI())
                : safeListObject(safeMap(context.getAqi()).get("historicalAQI")).stream()
                        .map(this::asStringObjectMap)
                        .toList();
        double contextAverage = history.stream()
                .mapToDouble(item -> number(item.get("aqi")))
                .filter(value -> value > 0)
                .average()
                .orElse(0.0);
        if (contextAverage > 0) {
            return contextAverage;
        }
        if (sensorDataRepository == null || cityId == null || cityId.isBlank()) {
            return 0.0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(72));
        return sensorDataRepository.findByTimestampBetween(cutoff, Instant.now()).stream()
                .filter(item -> cityId.equalsIgnoreCase(item.getCityId()))
                .mapToDouble(this::storedAqi)
                .filter(value -> value > 0)
                .average()
                .orElse(0.0);
    }

    private int historicalAqiCount(CityEnvironmentalContext context, String cityId) {
        List<Map<String, Object>> history = !safeList(context.getHistoricalAQI()).isEmpty()
                ? safeList(context.getHistoricalAQI())
                : safeListObject(safeMap(context.getAqi()).get("historicalAQI")).stream()
                        .map(this::asStringObjectMap)
                        .toList();
        int contextCount = (int) history.stream()
                .mapToDouble(item -> number(item.get("aqi")))
                .filter(value -> value > 0)
                .count();
        if (sensorDataRepository == null || cityId == null || cityId.isBlank()) {
            return contextCount;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(72));
        int storedCount = (int) sensorDataRepository.findByTimestampBetween(cutoff, Instant.now()).stream()
                .filter(item -> cityId.equalsIgnoreCase(item.getCityId()) && storedAqi(item) > 0)
                .count();
        return Math.max(contextCount, storedCount);
    }

    private boolean hasStoredHistory(String cityId) {
        if (sensorDataRepository == null || cityId == null || cityId.isBlank()) {
            return false;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(72));
        return sensorDataRepository.findByTimestampBetween(cutoff, Instant.now()).stream()
                .anyMatch(item -> cityId.equalsIgnoreCase(item.getCityId()) && storedAqi(item) > 0);
    }

    private SensorData latestStoredSnapshot(String cityId) {
        if (sensorDataRepository == null || cityId == null || cityId.isBlank()) {
            return null;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(72));
        return sensorDataRepository.findByTimestampBetween(cutoff, Instant.now()).stream()
                .filter(item -> cityId.equalsIgnoreCase(item.getCityId()) && storedAqi(item) > 0)
                .max((left, right) -> left.getTimestamp().compareTo(right.getTimestamp()))
                .orElse(null);
    }

    private double storedAqi(SensorData item) {
        return item != null && item.getPollutants() != null && item.getPollutants().getAqi() != null
                ? item.getPollutants().getAqi()
                : 0.0;
    }

    private Map<String, Object> firstStation(CityEnvironmentalContext context) {
        List<Object> stations = safeListObject(safeMap(context.getAqi()).get("stations"));
        if (!stations.isEmpty()) {
            return asStringObjectMap(stations.get(0));
        }
        return Map.of();
    }

    private Map<String, Object> pollutants(CityEnvironmentalContext context, Map<String, Object> firstStation) {
        Map<String, Object> stationPollutants = asStringObjectMap(firstStation.get("pollutants"));
        if (!stationPollutants.isEmpty()) {
            return stationPollutants;
        }
        return safeMap(context.getAqi());
    }

    private double rainProbability(CityEnvironmentalContext context) {
        Map<String, Object> weather = safeMap(context.getWeather());
        return firstPositive(
                number(weather.get("rainProbability")),
                number(weather.get("probabilityOfPrecipitation")),
                number(weather.get("pop")),
                forecastAverage(context, "precipitationProbability")
        );
    }

    private double forecastAverage(CityEnvironmentalContext context, String field) {
        return safeListObject(safeMap(context.getWeather()).get("hourlyForecast")).stream()
                .map(this::asStringObjectMap)
                .mapToDouble(item -> number(item.get(field)))
                .filter(value -> value > 0)
                .average()
                .orElse(0.0);
    }

    private boolean thermalAnomaly(CityEnvironmentalContext context) {
        Map<String, Object> satellite = safeMap(context.getSatellite());
        return truthy(satellite.get("thermalAnomaly"))
                || truthy(satellite.get("fireDetected"))
                || number(satellite.get("thermalAnomalyCount")) > 0;
    }

    private int highDustConstructionCount(CityEnvironmentalContext context) {
        return (int) safeList(context.getConstruction()).stream()
                .filter(site -> lower(string(site.get("dustRiskLevel"))).contains("high"))
                .count();
    }

    private int highRiskIndustrialCount(CityEnvironmentalContext context) {
        return (int) safeList(context.getIndustries()).stream()
                .filter(site -> lower(string(site.get("riskLevel"))).contains("high"))
                .count();
    }

    private double populationDensity(CityEnvironmentalContext context) {
        Map<String, Object> population = safeMap(context.getPopulation());
        return firstPositive(
                number(population.get("populationDensity")),
                number(population.get("density")),
                number(population.get("population"))
        );
    }

    private double providerConfidence(CityEnvironmentalContext context) {
        Map<String, Double> confidence = context.getProviderConfidence() != null ? context.getProviderConfidence() : Map.of();
        return confidence.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.30);
    }

    private double dataFreshness(Instant timestamp) {
        long ageMinutes = Math.max(0, Duration.between(timestamp, Instant.now()).toMinutes());
        if (ageMinutes <= 30) return 1.0;
        if (ageMinutes <= 180) return 0.85;
        if (ageMinutes <= 720) return 0.65;
        if (ageMinutes <= 1440) return 0.45;
        return 0.25;
    }

    private String season(int month) {
        if (month == 12 || month <= 2) return "WINTER";
        if (month >= 3 && month <= 5) return "SUMMER";
        if (month >= 6 && month <= 9) return "MONSOON";
        return "POST_MONSOON";
    }

    private Object first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private double firstPositive(double... values) {
        for (double value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0.0;
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String buildSensorId(String cityId, String stationName, double latitude, double longitude, String fallback) {
        String safeCity = valueOrDefault(cityId, "UNKNOWN_CITY");
        if (stationName != null && !stationName.isBlank() && latitude != 0.0 && longitude != 0.0) {
            return safeCity + "|" + stationName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_")
                    + "|" + round(latitude) + "," + round(longitude);
        }
        if (stationName != null && !stationName.isBlank()) {
            return safeCity + "|" + stationName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        }
        return fallback;
    }

    private String round(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private String string(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private String lower(String value) {
        return value != null ? value.toLowerCase() : "";
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(string(value)) || "yes".equalsIgnoreCase(string(value));
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value != null ? value : Map.of();
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> value) {
        return value != null ? value : List.of();
    }

    private List<Object> safeListObject(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringObjectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
