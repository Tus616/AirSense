package com.airsense.api.controllers;

import com.airsense.api.forecast.AqiForecastEvaluationService;
import com.airsense.api.forecast.ForecastOrchestrator;
import com.airsense.api.forecast.ForecastRequest;
import com.airsense.api.forecast.ForecastResult;
import com.airsense.api.forecast.HistoricalForecastReplayService;
import com.airsense.api.fusion.CpcbAqiService;
import com.airsense.api.history.AirQualityHistoryService;
import com.airsense.api.history.CpcbAllStationCollectionService;
import com.airsense.api.history.HistoricalAirQualityIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/air-quality")
public class AirQualityHistoryController {
    private final HistoricalAirQualityIngestionService ingestionService;
    private final AirQualityHistoryService historyService;
    private final AqiForecastEvaluationService forecastEvaluationService;
    private final CpcbAqiService cpcbAqiService;
    private final CpcbAllStationCollectionService cpcbAllStationCollectionService;
    private final HistoricalForecastReplayService historicalForecastReplayService;
    @Qualifier("hyperlocalForecastOrchestrator")
    private final ForecastOrchestrator forecastOrchestrator;

    @PostMapping("/history/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, Object> body) {
        HistoricalAirQualityIngestionService.IngestionResult result = ingestionService.refresh(locationRequest(body));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("observation", result.observation());
        response.put("inserted", result.inserted());
        response.put("status", result.status());
        response.put("snapshotPersistenceStatus", result.status());
        response.put("trackedLocationStatus", result.trackedLocationStatus());
        response.put("snapshotId", result.observation() != null ? result.observation().getId() : null);
        response.put("locationKey", result.observation() != null ? result.observation().getLocationKey() : null);
        response.put("locationKeyVersion", result.observation() != null ? result.observation().getLocationKeyVersion() : null);
        response.put("provider", result.observation() != null ? result.observation().getProvider() : null);
        response.put("aqiStandard", result.observation() != null ? result.observation().getAqiStandard() : null);
        response.put("providerObservedAt", result.observation() != null ? result.observation().getProviderObservedAt() : null);
        response.put("warnings", result.warnings());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/history/cpcb-stations/refresh")
    public ResponseEntity<Map<String, Object>> refreshCpcbStations(@RequestBody Map<String, Object> body) {
        String city = cityOrUnknown(string(body.get("city")));
        String state = string(body.get("state"));
        String country = valueOrDefault(string(body.get("country")), "India");
        double lat = number(body.get("latitude")) != null ? number(body.get("latitude")) : 0.0;
        double lon = number(body.get("longitude")) != null ? number(body.get("longitude")) : 0.0;
        List<Map<String, Object>> stations = cpcbAqiService.fetchStationCatalogue(city, state, lat, lon);
        List<Map<String, Object>> refreshed = new ArrayList<>();
        for (Map<String, Object> station : stations) {
            Double stationLat = number(station.get("stationLatitude"));
            Double stationLon = number(station.get("stationLongitude"));
            if (stationLat == null || stationLon == null) continue;
            HistoricalAirQualityIngestionService.IngestionResult result = ingestionService.refresh(
                    new HistoricalAirQualityIngestionService.LocationRequest(
                            string(station.get("station")), city, state, country, stationLat, stationLon));
            refreshed.add(Map.of(
                    "station", string(station.get("station")),
                    "locationKey", result.observation() != null ? result.observation().getLocationKey() : "",
                    "stationKey", result.observation() != null ? result.observation().getStationKey() : "",
                    "status", result.status(),
                    "providerObservedAt", result.observation() != null ? result.observation().getProviderObservedAt() : ""
            ));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("city", city);
        response.put("state", state);
        response.put("stationCount", stations.size());
        response.put("refreshedCount", refreshed.size());
        response.put("stations", refreshed);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/history/cpcb-stations/refresh-all")
    public ResponseEntity<Map<String, Object>> refreshAllCpcbStations() {
        return ResponseEntity.ok(cpcbAllStationCollectionService.collectAllStations().asMap());
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> timeline(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country,
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false, defaultValue = "168") int hours) {
        return ResponseEntity.ok(historyService.timeline(cityOrUnknown(city), state, country, lat, lon, hours));
    }

    @GetMapping("/history/quality")
    public ResponseEntity<Map<String, Object>> quality(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country,
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false, defaultValue = "30") int days) {
        return ResponseEntity.ok(historyService.quality(cityOrUnknown(city), state, country, lat, lon, days));
    }

    @GetMapping("/forecast")
    public ResponseEntity<ForecastResult> forecast(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String cityName,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country,
            @RequestParam(required = false) String placeId,
            @RequestParam(required = false) String wardId,
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false) String horizons,
            @RequestParam(required = false, defaultValue = "168") Integer historyHours,
            @RequestParam(required = false, defaultValue = "true") boolean refreshCurrent) {
        String resolvedCity = cityOrUnknown(cityName != null && !cityName.isBlank() ? cityName : city);
        ForecastRequest request = ForecastRequest.builder()
                .cityId(resolvedCity)
                .cityName(resolvedCity)
                .state(state)
                .country(country)
                .placeId(placeId)
                .wardId(wardId)
                .latitude(lat)
                .longitude(lon)
                .build();
        return ResponseEntity.ok(forecastOrchestrator.forecast(request));
    }

    @GetMapping("/forecast/metrics")
    public ResponseEntity<Map<String, Object>> metrics(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "India") String country,
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(required = false, defaultValue = "30") int days) {
        return ResponseEntity.ok(forecastEvaluationService.metrics(cityOrUnknown(city), state, country, lat, lon, days));
    }

    @PostMapping("/forecast/historical-replay")
    public ResponseEntity<HistoricalForecastReplayService.HistoricalReplayResponse> historicalReplay(
            @RequestBody HistoricalForecastReplayService.HistoricalReplayRequest request) {
        return ResponseEntity.ok(historicalForecastReplayService.replay(request));
    }

    @GetMapping("/forecast/historical-replay/stations")
    public ResponseEntity<List<HistoricalForecastReplayService.HistoricalReplayStationResponse>> historicalReplayStations() {
        return ResponseEntity.ok(historicalForecastReplayService.stations());
    }

    @GetMapping("/forecast/historical-replay/diagnostics")
    public ResponseEntity<Map<String, Object>> historicalReplayDiagnostics() {
        return ResponseEntity.ok(historicalForecastReplayService.diagnostics());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformedRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "BAD_REQUEST",
                "message", "Malformed request body or timestamp.",
                "details", exception.getMostSpecificCause().getMessage()
        ));
    }

    private HistoricalAirQualityIngestionService.LocationRequest locationRequest(Map<String, Object> body) {
        return new HistoricalAirQualityIngestionService.LocationRequest(
                string(body.get("displayName")),
                cityOrUnknown(string(body.get("city"))),
                string(body.get("state")),
                valueOrDefault(string(body.get("country")), "India"),
                number(body.get("latitude")),
                number(body.get("longitude"))
        );
    }

    private String cityOrUnknown(String value) {
        return value != null && !value.isBlank() ? value : "UNKNOWN_PLACE";
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String string(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
