package com.airsense.api.controllers;

import com.airsense.api.attribution.AttributionRequest;
import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.attribution.PollutionAttributionService;
import com.airsense.api.services.EarthEngineService;
import com.airsense.api.services.OpenWeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/v1/air-quality")
@RequiredArgsConstructor
public class AirQualityController {

    private final OpenWeatherService openWeatherService;
    private final EarthEngineService earthEngineService;
    private final PollutionAttributionService pollutionAttributionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAirQuality(
            @RequestParam(required = true) Double lat,
            @RequestParam(required = true) Double lon) {
        
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid latitude or longitude"));
        }

        log.info("Fetching combined air quality and satellite insights for lat: {}, lon: {}", lat, lon);

        CompletableFuture<Map<String, Object>> openWeatherFuture = CompletableFuture.supplyAsync(() -> 
            openWeatherService.getLiveAirQuality(lat, lon)
        ).exceptionally(ex -> externalFallback("OpenWeather", ex.getMessage()));

        CompletableFuture<Map<String, Object>> earthEngineFuture = CompletableFuture.supplyAsync(() -> 
            earthEngineService.getSatelliteInsights(lat, lon)
        ).exceptionally(ex -> externalFallback("Google Earth Engine", ex.getMessage()));

        CompletableFuture.allOf(openWeatherFuture, earthEngineFuture).join();

        Map<String, Object> liveAirQuality = openWeatherFuture.join();
        Map<String, Object> satelliteInsights = earthEngineFuture.join();

        Map<String, Object> response = new HashMap<>();

        Map<String, Object> location = new HashMap<>();
        location.put("latitude", lat);
        location.put("longitude", lon);
        // Geocoding can be done separately or passed from frontend, using simple defaults for now.
        location.put("city", "Selected Location");
        location.put("state", "");
        location.put("country", "");
        response.put("location", location);

        response.put("liveAirQuality", liveAirQuality);
        response.put("satelliteInsights", satelliteInsights);

        response.put("riskAnalysis", calculateRiskAnalysis(liveAirQuality));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/source-attribution")
    public ResponseEntity<AttributionResult> getSourceAttribution(
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country,
            @RequestParam(required = false, defaultValue = "false") boolean refreshEvidence) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(pollutionAttributionService.attribute(AttributionRequest.builder()
                .cityId(city != null && !city.isBlank() ? city : "UNKNOWN_PLACE")
                .cityName(city)
                .state(state)
                .country(country)
                .latitude(lat)
                .longitude(lon)
                .refreshEvidence(refreshEvidence)
                .build()));
    }

    private Map<String, Object> calculateRiskAnalysis(Map<String, Object> liveAirQuality) {
        Map<String, Object> risk = new HashMap<>();
        
        if (liveAirQuality == null || !Boolean.TRUE.equals(liveAirQuality.get("available"))) {
            risk.put("score", 0);
            risk.put("level", "UNAVAILABLE");
            risk.put("reason", "No live data available.");
            risk.put("recommendedActions", List.of("Live provider data unavailable."));
            return risk;
        }

        int aqi = liveAirQuality.get("calculatedAqi") instanceof Number number ? number.intValue() : 0;

        risk.put("score", aqi);
        risk.put("openWeatherAqiIndex", liveAirQuality.get("openWeatherAqiIndex"));
        risk.put("openWeatherAqiCategory", liveAirQuality.get("openWeatherAqiCategory"));

        if (aqi <= 50) {
            risk.put("level", "LOW");
            risk.put("reason", "Air quality is considered satisfactory, and air pollution poses little or no risk.");
            risk.put("recommendedActions", List.of("Enjoy outdoor activities."));
        } else if (aqi <= 100) {
            risk.put("level", "MODERATE");
            risk.put("reason", "Air quality is acceptable; however, there may be a risk for some people, particularly those who are unusually sensitive to air pollution.");
            risk.put("recommendedActions", List.of("Unusually sensitive people should consider reducing prolonged or heavy exertion."));
        } else if (aqi <= 200) {
            risk.put("level", "HIGH");
            risk.put("reason", "Members of sensitive groups may experience health effects. The general public is less likely to be affected.");
            risk.put("recommendedActions", List.of(
                "Sensitive groups should reduce prolonged or heavy exertion.",
                "Keep windows closed to avoid indoor pollution."
            ));
        } else {
            risk.put("level", "SEVERE");
            risk.put("reason", "Health warning of emergency conditions: everyone is more likely to be affected.");
            risk.put("recommendedActions", List.of(
                "Everyone should avoid all physical activity outdoors.",
                "Wear an N95 mask if you must go outside.",
                "Use air purifiers indoors."
            ));
        }
        return risk;
    }

    private Map<String, Object> externalFallback(String provider, String reason) {
        log.warn("External API fallback provider={} reason={}", provider, reason);
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("provider", provider);
        fallback.put("available", false);
        fallback.put("fallback", true);
        fallback.put("reason", reason);
        return fallback;
    }
}
