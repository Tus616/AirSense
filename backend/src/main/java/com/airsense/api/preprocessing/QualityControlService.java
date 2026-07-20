package com.airsense.api.preprocessing;

import com.airsense.api.entities.SensorData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class QualityControlService {

    public SensorData applyQualityControl(SensorData data) {
        SensorData.QualityFlags flags = data.getQualityFlags();
        if (flags.getMissingFields() == null) flags.setMissingFields(new ArrayList<>());
        if (flags.getImputedFields() == null) flags.setImputedFields(new ArrayList<>());
        flags.setAnomalySmoothed(false);

        // Simple imputation for missing pollutants
        if (data.getPollutants() != null) {
            if (data.getPollutants().getPm25() == null) {
                flags.getMissingFields().add("pm25");
                flags.getImputedFields().add("pm25");
                data.getPollutants().setPm25(50.0); // Simple constant imputation
            }
            
            // Basic anomaly smoothing
            if (data.getPollutants().getPm25() > 999.0) {
                data.getPollutants().setPm25(999.0);
                flags.setAnomalySmoothed(true);
            }
        }

        return data;
    }
}
