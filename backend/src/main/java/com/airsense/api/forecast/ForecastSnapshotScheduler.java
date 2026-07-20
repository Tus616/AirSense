package com.airsense.api.forecast;

import com.airsense.api.decision.DecisionIntelligenceService;
import com.airsense.api.decision.DecisionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ForecastSnapshotScheduler {
    private final DecisionIntelligenceService decisionIntelligenceService;

    @Value("${forecast.snapshots.cities:DELHI|Delhi|Delhi|India|28.6139|77.2090,LUCKNOW|Lucknow|Uttar Pradesh|India|26.8467|80.9462}")
    private String configuredCities;

    @Scheduled(cron = "${forecast.snapshots.cron:0 20 * * * *}")
    public void collectScheduledSnapshots() {
        for (SnapshotCity city : cities()) {
            try {
                decisionIntelligenceService.decide(DecisionRequest.builder()
                        .cityId(city.cityId())
                        .cityName(city.cityName())
                        .state(city.state())
                        .country(city.country())
                        .latitude(city.latitude())
                        .longitude(city.longitude())
                        .build());
                log.info("ForecastSnapshotScheduler collected cityId={}", city.cityId());
            } catch (Exception e) {
                log.warn("ForecastSnapshotScheduler failed cityId={} reason={}", city.cityId(), e.getMessage());
            }
        }
    }

    private List<SnapshotCity> cities() {
        return Arrays.stream(configuredCities.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::parse)
                .toList();
    }

    private SnapshotCity parse(String text) {
        String[] parts = text.split("\\|");
        if (parts.length < 6) {
            throw new IllegalArgumentException("Invalid forecast snapshot city config: " + text);
        }
        return new SnapshotCity(
                parts[0],
                parts[1],
                parts[2],
                parts[3],
                Double.parseDouble(parts[4]),
                Double.parseDouble(parts[5])
        );
    }

    private record SnapshotCity(String cityId, String cityName, String state, String country, double latitude, double longitude) {}
}
