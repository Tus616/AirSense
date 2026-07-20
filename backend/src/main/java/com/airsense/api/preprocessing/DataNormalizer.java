package com.airsense.api.preprocessing;

import com.airsense.api.entities.SensorData;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class DataNormalizer {

    public SensorData normalize(SensorData data) {
        // Truncate timestamp to hourly bucket
        if (data.getTimestamp() != null) {
            data.setTimestamp(data.getTimestamp().truncatedTo(ChronoUnit.HOURS));
        } else {
            data.setTimestamp(Instant.now().truncatedTo(ChronoUnit.HOURS));
        }

        // Initialize metadata flags if not present
        if (data.getQualityFlags() == null) {
            data.setQualityFlags(new SensorData.QualityFlags());
        }
        
        return data;
    }
}
