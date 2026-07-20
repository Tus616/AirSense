package com.airsense.api.providers;

import com.airsense.api.entities.SensorData;
import com.airsense.api.services.OpenWeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WeatherProvider {

    private static final Logger logger = LoggerFactory.getLogger(WeatherProvider.class);
    private final OpenWeatherService openWeatherService;

    public WeatherProvider(OpenWeatherService openWeatherService) {
        this.openWeatherService = openWeatherService;
    }

    public List<SensorData> enrichWithWeather(List<SensorData> baseData) {
        logger.info("Enriching {} records with weather data provider=OpenWeather", baseData.size());

        baseData.forEach(data -> {
            double lat = latitude(data);
            double lon = longitude(data);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                logger.warn("OpenWeather enrichment skipped sensorId={} reason=missing_coordinates", data.getSensorId());
                data.setWeather(SensorData.Weather.builder()
                        .temperature(existingTemperature(data))
                        .humidity(existingHumidity(data))
                        .windSpeed(existingWindSpeed(data))
                        .windDirection(existingWindDirection(data))
                        .build());
                return;
            }
            Map<String, Object> weather = openWeatherService.getCurrentWeather(lat, lon);

            data.setWeather(SensorData.Weather.builder()
                    .temperature(numberOrDefault(weather.get("temperature"), existingTemperature(data)))
                    .humidity(numberOrDefault(weather.get("humidity"), existingHumidity(data)))
                    .windSpeed(numberOrDefault(weather.get("windSpeed"), existingWindSpeed(data)))
                    .windDirection(numberOrDefault(weather.get("windDirection"), existingWindDirection(data)))
                    .build());

            if (Boolean.TRUE.equals(weather.get("available"))) {
                logger.info("OpenWeather enrichment success sensorId={} lat={} lon={}", data.getSensorId(), lat, lon);
            } else {
                logger.warn("OpenWeather enrichment fallback sensorId={} reason={}", data.getSensorId(), weather.get("reason"));
            }
        });

        return baseData;
    }

    private double longitude(SensorData data) {
        if (data.getCoordinates() != null && data.getCoordinates().getCoordinates() != null
                && !data.getCoordinates().getCoordinates().isEmpty()) {
            return data.getCoordinates().getCoordinates().get(0);
        }
        return Double.NaN;
    }

    private double latitude(SensorData data) {
        if (data.getCoordinates() != null && data.getCoordinates().getCoordinates() != null
                && data.getCoordinates().getCoordinates().size() > 1) {
            return data.getCoordinates().getCoordinates().get(1);
        }
        return Double.NaN;
    }

    private double numberOrDefault(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private double existingTemperature(SensorData data) {
        return data.getWeather() != null && data.getWeather().getTemperature() != null ? data.getWeather().getTemperature() : 0.0;
    }

    private double existingHumidity(SensorData data) {
        return data.getWeather() != null && data.getWeather().getHumidity() != null ? data.getWeather().getHumidity() : 0.0;
    }

    private double existingWindSpeed(SensorData data) {
        return data.getWeather() != null && data.getWeather().getWindSpeed() != null ? data.getWeather().getWindSpeed() : 0.0;
    }

    private double existingWindDirection(SensorData data) {
        return data.getWeather() != null && data.getWeather().getWindDirection() != null ? data.getWeather().getWindDirection() : 0.0;
    }
}
