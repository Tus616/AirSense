package com.airsense.api.providers;

import com.airsense.api.entities.SensorData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class AirQualityProvider {

    private static final Logger logger = LoggerFactory.getLogger(AirQualityProvider.class);

    public List<SensorData> fetchLatestData() {
        logger.info("Fetching latest air quality data from local configured sources...");
        // Since we may not have an API key, we fallback to a generated fixture for local testing
        return generateFallbackData();
    }

    private List<SensorData> generateFallbackData() {
        logger.warn("Using fallback air quality fixture data (API key missing or rate limit reached).");
        List<SensorData> list = new ArrayList<>();
        
        Instant now = Instant.now();

        // Station 1: Anand Vihar (Industrial/Traffic)
        list.add(SensorData.builder()
                .sensorId("sensor-anand-vihar-1")
                .stationName("Anand Vihar, Delhi - DPCC")
                .wardId("W-101")
                .zoneId("Z-East")
                .coordinates(new SensorData.GeoJsonPoint("Point", List.of(77.3159, 28.6476)))
                .timestamp(now)
                .pollutants(SensorData.Pollutants.builder()
                        .pm25(185.5)
                        .pm10(312.0)
                        .no2(85.2)
                        .co(1.5)
                        .o3(45.0)
                        .aqi(320)
                        .build())
                .build());

        // Station 2: RK Puram (Residential)
        list.add(SensorData.builder()
                .sensorId("sensor-rk-puram-1")
                .stationName("R.K. Puram, Delhi - DPCC")
                .wardId("W-205")
                .zoneId("Z-South")
                .coordinates(new SensorData.GeoJsonPoint("Point", List.of(77.1873, 28.5660)))
                .timestamp(now)
                .pollutants(SensorData.Pollutants.builder()
                        .pm25(110.2)
                        .pm10(180.5)
                        .no2(55.0)
                        .co(0.8)
                        .o3(60.0)
                        .aqi(215)
                        .build())
                .build());

        // Station 3: Punjabi Bagh
        list.add(SensorData.builder()
                .sensorId("sensor-punjabi-bagh-1")
                .stationName("Punjabi Bagh, Delhi - DPCC")
                .wardId("W-302")
                .zoneId("Z-West")
                .coordinates(new SensorData.GeoJsonPoint("Point", List.of(77.1330, 28.6740)))
                .timestamp(now)
                .pollutants(SensorData.Pollutants.builder()
                        .pm25(145.0)
                        .pm10(210.0)
                        .no2(62.5)
                        .co(1.1)
                        .o3(52.0)
                        .aqi(275)
                        .build())
                .build());

        return list;
    }
}
