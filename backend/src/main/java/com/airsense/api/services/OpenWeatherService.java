package com.airsense.api.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Service
public class OpenWeatherService {

    private final RestTemplate restTemplate;

    @Value("${openweather.api.key:}")
    private String apiKey;

    @Value("${openweather.base.url:https://api.openweathermap.org/data/2.5}")
    private String baseUrl;

    public OpenWeatherService(RestTemplateBuilder builder,
                              @Value("${openweather.request-timeout-seconds:${OPENWEATHER_REQUEST_TIMEOUT_SECONDS:10}}") long requestTimeoutSeconds) {
        long timeout = Math.max(1, requestTimeoutSeconds);
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(timeout))
                .setReadTimeout(Duration.ofSeconds(timeout))
                .build();
    }

    public Map<String, Object> getLiveAirQuality(double lat, double lon) {
        if (!validCoordinates(lat, lon)) {
            log.warn("OpenWeather fallback reason=invalid_coordinates lat={} lon={}", lat, lon);
            return unavailableResponse("Invalid coordinates");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("OpenWeather fallback reason=api_key_missing lat={} lon={}", lat, lon);
            return unavailableResponse("API key not configured");
        }

        String url = String.format("%s/air_pollution?lat=%s&lon=%s&appid=%s", baseUrl, lat, lon, apiKey);
        
        int maxRetries = 3;
        int attempt = 0;
        
        while (attempt < maxRetries) {
            try {
                log.info("OpenWeather request attempt={} lat={} lon={}", attempt + 1, lat, lon);
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> body = response.getBody();
                    List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("list");
                    
                    if (list != null && !list.isEmpty()) {
                        Map<String, Object> data = list.get(0);
                        Map<String, Object> components = (Map<String, Object>) data.get("components");
                        Map<String, Object> main = (Map<String, Object>) data.get("main");
                        if (components == null || main == null || !(main.get("aqi") instanceof Number)) {
                            log.warn("OpenWeather fallback reason=missing_components_or_aqi lat={} lon={}", lat, lon);
                            return unavailableResponse("OpenWeather response missing pollutant components or AQI");
                        }
                        
                        Map<String, Object> result = new HashMap<>();
                        
                        int owAqi = ((Number) main.get("aqi")).intValue();
                        Integer calculatedAqi = calculatedAqi(components);
                        String providerTimestamp = data.get("dt") instanceof Number dt
                                ? Instant.ofEpochSecond(dt.longValue()).toString()
                                : Instant.now().toString();
                        
                        result.put("openWeatherAqiIndex", owAqi);
                        result.put("openWeatherAqiCategory", openWeatherAqiCategory(owAqi));
                        result.put("calculatedAqi", calculatedAqi);
                        result.put("aqi", owAqi);
                        result.put("pm25", components.get("pm2_5"));
                        result.put("pm10", components.get("pm10"));
                        result.put("no2", components.get("no2"));
                        result.put("so2", components.get("so2"));
                        result.put("co", components.get("co"));
                        result.put("o3", components.get("o3"));
                        result.put("nh3", components.get("nh3"));
                        result.put("timestamp", providerTimestamp);
                        result.put("lastUpdated", providerTimestamp);
                        result.put("provider", "OpenWeather");
                        result.put("available", true);
                        result.put("fallback", false);
                        result.put("httpStatus", response.getStatusCode().value());
                        
                        log.info("OpenWeather air_pollution success provider=OpenWeather lat={} lon={} httpStatus={} responseTimestamp={} fallbackUsed=false cacheStatus=MISS openweatherAqiIndex={} calculatedAqi={}",
                                lat, lon, response.getStatusCode().value(), providerTimestamp, owAqi, calculatedAqi);
                        return result;
                    }
                }
                return unavailableResponse("Empty or unexpected OpenWeather response", response.getStatusCode());
            } catch (Exception e) {
                attempt++;
                log.warn("OpenWeather failure attempt={}/{} reason={} lat={} lon={}", attempt, maxRetries, e.getMessage(), lat, lon);
                try {
                    Thread.sleep((long) Math.pow(2, attempt) * 500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        log.error("OpenWeather fallback reason=retries_exhausted lat={} lon={}", lat, lon);
        return unavailableResponse("OpenWeather API unavailable after retries");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCurrentWeather(double lat, double lon) {
        if (!validCoordinates(lat, lon)) {
            log.warn("OpenWeather weather fallback reason=invalid_coordinates lat={} lon={}", lat, lon);
            return unavailableWeatherResponse("Invalid coordinates");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("OpenWeather weather fallback reason=api_key_missing lat={} lon={}", lat, lon);
            return unavailableWeatherResponse("API key not configured");
        }

        String url = String.format("%s/weather?lat=%s&lon=%s&appid=%s&units=metric", baseUrl, lat, lon, apiKey);
        try {
            log.info("OpenWeather weather request lat={} lon={}", lat, lon);
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Map<String, Object> main = (Map<String, Object>) body.get("main");
                Map<String, Object> wind = (Map<String, Object>) body.get("wind");
                if (main == null || wind == null) {
                    log.warn("OpenWeather weather fallback reason=missing_main_or_wind lat={} lon={}", lat, lon);
                    return unavailableWeatherResponse("OpenWeather weather response missing main or wind data");
                }

                Map<String, Object> result = new HashMap<>();
                result.put("temperature", main.get("temp"));
                result.put("humidity", main.get("humidity"));
                result.put("pressure", main.get("pressure"));
                result.put("windSpeed", wind.get("speed"));
                result.put("windDirection", wind.get("deg"));
                result.put("rainfall", rainfall(body.get("rain")));
                result.put("hourlyForecast", getHourlyWeatherForecast(lat, lon));
                String providerTimestamp = body.get("dt") instanceof Number dt
                        ? Instant.ofEpochSecond(dt.longValue()).toString()
                        : Instant.now().toString();
                result.put("timestamp", providerTimestamp);
                result.put("lastUpdated", providerTimestamp);
                result.put("provider", "OpenWeather");
                result.put("available", true);
                result.put("fallback", false);
                result.put("httpStatus", response.getStatusCode().value());
                log.info("OpenWeather weather success provider=OpenWeather lat={} lon={} httpStatus={} responseTimestamp={} fallbackUsed=false cacheStatus=MISS",
                        lat, lon, response.getStatusCode().value(), providerTimestamp);
                return result;
            }
            log.warn("OpenWeather weather fallback reason=empty_or_unexpected_response status={} lat={} lon={}",
                    response.getStatusCode(), lat, lon);
            return unavailableWeatherResponse("Empty or unexpected OpenWeather weather response", response.getStatusCode());
        } catch (Exception e) {
            log.warn("OpenWeather weather failure reason={} lat={} lon={}", e.getMessage(), lat, lon);
            log.warn("OpenWeather weather fallback reason=api_unavailable lat={} lon={}", lat, lon);
            return unavailableWeatherResponse("OpenWeather weather API unavailable: " + e.getMessage());
        }
    }
    
    private Map<String, Object> unavailableResponse(String reason) {
        return unavailableResponse(reason, null);
    }

    private Map<String, Object> unavailableResponse(String reason, HttpStatusCode statusCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("aqi", null);
        result.put("openWeatherAqiIndex", null);
        result.put("openWeatherAqiCategory", "Unavailable");
        result.put("calculatedAqi", null);
        result.put("pm25", null);
        result.put("pm10", null);
        result.put("no2", null);
        result.put("so2", null);
        result.put("co", null);
        result.put("o3", null);
        result.put("nh3", null);
        result.put("timestamp", Instant.now().toString());
        result.put("provider", "OpenWeather");
        result.put("available", false);
        result.put("fallback", true);
        result.put("reason", reason);
        result.put("httpStatus", statusCode != null ? statusCode.value() : null);
        result.put("lastUpdated", result.get("timestamp"));
        return result;
    }

    private Map<String, Object> unavailableWeatherResponse(String reason) {
        return unavailableWeatherResponse(reason, null);
    }

    private Map<String, Object> unavailableWeatherResponse(String reason, HttpStatusCode statusCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("temperature", null);
        result.put("humidity", null);
        result.put("windSpeed", null);
        result.put("windDirection", null);
        result.put("pressure", null);
        result.put("rainfall", null);
        result.put("hourlyForecast", List.of());
        result.put("timestamp", Instant.now().toString());
        result.put("provider", "OpenWeather");
        result.put("available", false);
        result.put("fallback", true);
        result.put("reason", reason);
        result.put("httpStatus", statusCode != null ? statusCode.value() : null);
        result.put("lastUpdated", result.get("timestamp"));
        return result;
    }

    private String openWeatherAqiCategory(int index) {
        return switch (index) {
            case 1 -> "Good";
            case 2 -> "Fair";
            case 3 -> "Moderate";
            case 4 -> "Poor";
            case 5 -> "Very Poor";
            default -> "Unavailable";
        };
    }

    private Integer calculatedAqi(Map<String, Object> components) {
        // India NAQI-style concentration breakpoints. Each pollutant sub-index is
        // interpolated within its concentration band; the displayed calculatedAqi
        // is the maximum available pollutant sub-index.
        return List.of(
                        subIndex(number(components.get("pm2_5")), new double[]{0, 30, 60, 90, 120, 250, 500}, new int[]{0, 50, 100, 200, 300, 400, 500}),
                        subIndex(number(components.get("pm10")), new double[]{0, 50, 100, 250, 350, 430, 600}, new int[]{0, 50, 100, 200, 300, 400, 500}),
                        subIndex(number(components.get("no2")), new double[]{0, 40, 80, 180, 280, 400, 800}, new int[]{0, 50, 100, 200, 300, 400, 500}),
                        subIndex(number(components.get("so2")), new double[]{0, 40, 80, 380, 800, 1600, 2400}, new int[]{0, 50, 100, 200, 300, 400, 500}),
                        subIndex(number(components.get("o3")), new double[]{0, 50, 100, 168, 208, 748, 1000}, new int[]{0, 50, 100, 200, 300, 400, 500}),
                        subIndex(number(components.get("co")), new double[]{0, 1000, 2000, 10000, 17000, 34000, 50000}, new int[]{0, 50, 100, 200, 300, 400, 500})
                ).stream()
                .filter(value -> value != null)
                .mapToInt(Integer::intValue)
                .max()
                .stream()
                .boxed()
                .findFirst()
                .orElse(null);
    }

    private Integer subIndex(Double concentration, double[] concentrationBreakpoints, int[] indexBreakpoints) {
        if (concentration == null || concentration < 0) return null;
        for (int i = 1; i < concentrationBreakpoints.length; i++) {
            if (concentration <= concentrationBreakpoints[i]) {
                double cLow = concentrationBreakpoints[i - 1];
                double cHigh = concentrationBreakpoints[i];
                int iLow = indexBreakpoints[i - 1];
                int iHigh = indexBreakpoints[i];
                return (int) Math.round(((iHigh - iLow) / (cHigh - cLow)) * (concentration - cLow) + iLow);
            }
        }
        return 500;
    }

    private Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getHourlyWeatherForecast(double lat, double lon) {
        String url = String.format("%s/forecast?lat=%s&lon=%s&appid=%s&units=metric", baseUrl, lat, lon, apiKey);
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return List.of();
            }
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("list");
            if (rows == null) {
                return List.of();
            }
            return rows.stream()
                    .limit(24)
                    .map(row -> {
                        Map<String, Object> main = (Map<String, Object>) row.getOrDefault("main", Map.of());
                        Map<String, Object> wind = (Map<String, Object>) row.getOrDefault("wind", Map.of());
                        return Map.of(
                                "timestamp", row.get("dt") instanceof Number dt ? Instant.ofEpochSecond(dt.longValue()).toString() : "",
                                "temperature", main.getOrDefault("temp", 0.0),
                                "humidity", main.getOrDefault("humidity", 0.0),
                                "pressure", main.getOrDefault("pressure", 0.0),
                                "windSpeed", wind.getOrDefault("speed", 0.0),
                                "windDirection", wind.getOrDefault("deg", 0.0),
                                "rainfall", rainfall(row.get("rain")),
                                "precipitationProbability", row.getOrDefault("pop", 0.0)
                        );
                    })
                    .toList();
        } catch (RestClientException e) {
            log.warn("OpenWeather forecast unavailable reason={} lat={} lon={}", e.getMessage(), lat, lon);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Double rainfall(Object rain) {
        if (rain instanceof Map<?, ?> map) {
            Object threeHour = map.get("3h");
            Object oneHour = map.get("1h");
            Double value = number(threeHour != null ? threeHour : oneHour);
            return value != null ? value : 0.0;
        }
        return 0.0;
    }

    private boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0;
    }
}
