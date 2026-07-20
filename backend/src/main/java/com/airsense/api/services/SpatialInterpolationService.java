package com.airsense.api.services;

import com.airsense.api.entities.GridCell;
import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.GridForecast;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SpatialInterpolationService {

    private static final int POWER = 2;

    /**
     * Interpolates base station predictions across the grid using IDW.
     * In a real scenario, stations have fixed lat/lon. Here we mock station locations
     * based on their ward logic or random spread across the city bounding box.
     */
    public List<GridForecast> interpolateGrid(List<GridCell> cells, List<Prediction> stationPredictions) {
        List<GridForecast> forecasts = new ArrayList<>();

        // Mock station coordinates (in real life, fetch from Station registry)
        Map<String, double[]> stationLocations = new HashMap<>();
        double baseLat = 28.55;
        double baseLon = 77.15;
        for (int i = 0; i < stationPredictions.size(); i++) {
            stationLocations.put(
                stationPredictions.get(i).getSensorId(), 
                new double[]{baseLon + (i * 0.01), baseLat + (i * 0.01)}
            );
        }

        for (GridCell cell : cells) {
            double cellLon = cell.getCentroid().getX();
            double cellLat = cell.getCentroid().getY();

            GridForecast gf = new GridForecast();
            gf.setGridId(cell.getGridCellId());
            gf.setCityId(cell.getCityId());
            gf.setWardId(cell.getWardId());
            gf.setLocation(new double[]{cellLon, cellLat});
            gf.setGeneratedAt(java.time.Instant.now());
            gf.setHorizonHours(72);

            // Calculate distances to all stations
            List<StationDistance> distances = new ArrayList<>();
            for (Prediction p : stationPredictions) {
                double[] sLoc = stationLocations.get(p.getSensorId());
                double dist = calculateDistance(cellLon, cellLat, sLoc[0], sLoc[1]);
                distances.add(new StationDistance(p, dist));
            }

            // Sort by distance and take nearest 4 (or less)
            distances.sort((a, b) -> Double.compare(a.distance, b.distance));
            List<StationDistance> nearest = distances.stream().limit(4).collect(Collectors.toList());

            List<GridForecast.HourlyPrediction> gridHourly = new ArrayList<>();
            
            // Assume all predictions have 72 hours
            for (int h = 0; h < 72; h++) {
                double numAqi = 0;
                double numPm25 = 0;
                double den = 0;

                boolean exactMatch = false;

                for (StationDistance sd : nearest) {
                    if (sd.distance < 0.0001) { // practically zero distance
                        GridForecast.HourlyPrediction hp = new GridForecast.HourlyPrediction();
                        var sp = sd.prediction.getPredictions().get(h);
                        hp.setPredictedAqi(sp.getPredictedAqi());
                        hp.setPredictedPm25(sp.getPredictedPm25());
                        hp.setTimestamp(java.time.Instant.parse(sp.getTimestamp()));
                        hp.setCategory(sp.getCategory());
                        gridHourly.add(hp);
                        exactMatch = true;
                        break;
                    }
                    
                    double weight = 1.0 / Math.pow(sd.distance, POWER);
                    var sp = sd.prediction.getPredictions().get(h);
                    
                    numAqi += sp.getPredictedAqi() * weight;
                    numPm25 += sp.getPredictedPm25() * weight;
                    den += weight;
                }

                if (!exactMatch && den > 0) {
                    GridForecast.HourlyPrediction hp = new GridForecast.HourlyPrediction();
                    var sampleSp = nearest.get(0).prediction.getPredictions().get(h);
                    
                    int interpAqi = (int) Math.round(numAqi / den);
                    double interpPm25 = numPm25 / den;

                    hp.setPredictedAqi(interpAqi);
                    hp.setPredictedPm25(interpPm25);
                    hp.setTimestamp(java.time.Instant.parse(sampleSp.getTimestamp()));
                    hp.setCategory(getCategory(interpAqi));
                    gridHourly.add(hp);
                }
            }

            gf.setPredictions(gridHourly);

            Map<String, Object> meta = new HashMap<>();
            meta.put("method", "IDW");
            meta.put("power", POWER);
            meta.put("nearestStations", nearest.size());
            gf.setInterpolationMetadata(meta);

            // Copy seasonal multiplier from the first prediction
            if (!stationPredictions.isEmpty()) {
                Double sm = stationPredictions.get(0).getSeasonalMultiplier();
                if (sm != null) {
                    Map<String, Object> sMeta = new HashMap<>();
                    sMeta.put("seasonalMultiplier", sm);
                    gf.setSeasonalMetadata(sMeta);
                }
            }

            forecasts.add(gf);
        }

        return forecasts;
    }

    private double calculateDistance(double lon1, double lat1, double lon2, double lat2) {
        // Simple Euclidean distance for local grid (approximate)
        return Math.sqrt(Math.pow(lon1 - lon2, 2) + Math.pow(lat1 - lat2, 2));
    }

    private String getCategory(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Satisfactory";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "Poor";
        if (aqi <= 400) return "Very Poor";
        return "Severe";
    }

    private static class StationDistance {
        Prediction prediction;
        double distance;
        StationDistance(Prediction p, double d) {
            this.prediction = p;
            this.distance = d;
        }
    }
}
