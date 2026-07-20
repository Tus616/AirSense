package com.airsense.api.services;

import com.airsense.api.entities.GridForecast;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DispersionAdjustmentService {

    /**
     * Applies a deterministic wind-based metadata pass. Live AQI forecasts must not be randomly perturbed.
     */
    public void adjustForDispersion(List<GridForecast> gridForecasts) {
        double windSpeed = 0.0;
        String windDir = "UNAVAILABLE";
        
        for (GridForecast gf : gridForecasts) {
            boolean isDownwind = false;
            double adjustmentFactor = 1.0;

            for (GridForecast.HourlyPrediction hp : gf.getPredictions()) {
                int adjAqi = (int) (hp.getPredictedAqi() * adjustmentFactor);
                double adjPm25 = hp.getPredictedPm25() * adjustmentFactor;
                
                hp.setPredictedAqi(adjAqi);
                hp.setPredictedPm25(adjPm25);
                hp.setCategory(getCategory(adjAqi));
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("windSpeed", windSpeed);
            meta.put("windDirection", windDir);
            meta.put("adjustmentApplied", isDownwind);
            meta.put("averageAdjustmentFactor", adjustmentFactor);
            meta.put("limitations", "No real wind vector was supplied to the legacy grid forecast path; random dispersion adjustment disabled.");
            
            gf.setDispersionMetadata(meta);
        }
    }

    private String getCategory(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Satisfactory";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "Poor";
        if (aqi <= 400) return "Very Poor";
        return "Severe";
    }
}
