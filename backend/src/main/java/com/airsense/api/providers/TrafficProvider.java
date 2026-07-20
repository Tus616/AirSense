package com.airsense.api.providers;

import com.airsense.api.entities.SensorData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrafficProvider {

    private static final Logger logger = LoggerFactory.getLogger(TrafficProvider.class);

    public List<SensorData> enrichWithTraffic(List<SensorData> baseData) {
        logger.info("Enriching {} records with traffic data (TomTom/OpenStreetMap)", baseData.size());
        
        baseData.forEach(data -> {
            // Fallback fixture implementation
            double congestion;
            double speed;
            if (data.getZoneId().contains("East")) {
                congestion = 0.85; // High traffic
                speed = 15.5;
            } else {
                congestion = 0.45;
                speed = 35.0;
            }

            data.setTraffic(SensorData.Traffic.builder()
                    .congestionIndex(congestion)
                    .averageSpeed(speed)
                    .flow(1500.0 * congestion)
                    .build());
        });
        
        return baseData;
    }
}
