package com.airsense.api.providers;

import com.airsense.api.entities.SensorData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LandUseProvider {

    private static final Logger logger = LoggerFactory.getLogger(LandUseProvider.class);

    public List<SensorData> enrichWithLandUse(List<SensorData> baseData) {
        logger.info("Enriching {} records with land use data (OSM)", baseData.size());
        
        baseData.forEach(data -> {
            // Fallback fixture implementation
            String primaryType = "residential";
            if (data.getStationName().contains("Anand Vihar")) {
                primaryType = "industrial_transport";
            }

            data.setLandUse(SensorData.LandUse.builder()
                    .primaryType(primaryType)
                    .tags(List.of("urban", "high_density"))
                    .build());
        });
        
        return baseData;
    }
}
