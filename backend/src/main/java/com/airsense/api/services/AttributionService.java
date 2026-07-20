package com.airsense.api.services;

import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.SensorData;
import com.airsense.api.repositories.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AttributionService {

    @Autowired
    private SensorDataRepository sensorDataRepository;

    public String determinePrimarySource(Prediction prediction) {
        // Fallback default
        String fallbackSource = "mixed urban sources";
        
        Object rawSummary = prediction.getFeatureImportanceSummary();
        if (rawSummary == null || !(rawSummary instanceof Map)) {
            return fallbackSource;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> featureImportance = (Map<String, Object>) rawSummary;

        // Convert values to double for sorting
        Map<String, Double> importanceMap = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : featureImportance.entrySet()) {
            double val = entry.getValue() instanceof Number ? ((Number) entry.getValue()).doubleValue() : 0.0;
            importanceMap.put(entry.getKey(), val);
        }

        // Find the top feature by importance
        String topFeature = importanceMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");

        // Get latest sensor data for context
        Optional<SensorData> latestDataOpt = sensorDataRepository.findFirstBySensorIdOrderByTimestampDesc(prediction.getSensorId());

        // Simple heuristic rules combining XGBoost importance and SensorData context
        if (topFeature.contains("Traffic") || topFeature.contains("Time of Day")) {
            if (latestDataOpt.isPresent() && latestDataOpt.get().getTraffic() != null) {
                if (latestDataOpt.get().getTraffic().getCongestionIndex() != null && latestDataOpt.get().getTraffic().getCongestionIndex() > 0.6) {
                    return "heavy traffic emissions";
                }
            }
            return "traffic emissions";
        }

        if (topFeature.contains("Season") || topFeature.contains("Temperature")) {
            return "seasonal meteorological conditions";
        }
        
        if (topFeature.contains("Wind Speed")) {
            return "wind-blown dust and transported pollutants";
        }

        if (latestDataOpt.isPresent() && latestDataOpt.get().getLandUse() != null) {
            String landUseType = latestDataOpt.get().getLandUse().getPrimaryType();
            if ("Industrial".equalsIgnoreCase(landUseType)) {
                return "industrial activity";
            }
        }

        if (topFeature.contains("Recent AQI") || topFeature.contains("Daily PM25 Pattern")) {
             return "accumulated local emissions";
        }

        return fallbackSource;
    }
}
