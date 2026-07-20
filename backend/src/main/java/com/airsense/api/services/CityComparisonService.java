package com.airsense.api.services;

import com.airsense.api.dto.CitySnapshotDto;
import com.airsense.api.entities.*;
import com.airsense.api.repositories.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CityComparisonService {

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private SensorDataRepository sensorDataRepository;

    @Autowired
    private PredictionRepository predictionRepository;

    @Autowired
    private AttributionResultRepository attributionResultRepository;

    @Autowired
    private EnforcementRecommendationRepository enforcementRepository;

    @Autowired
    private AdvisoryRepository advisoryRepository;

    public List<String> getCities() {
        List<String> cityIds = new ArrayList<>();
        cityRepository.findAll().forEach(c -> cityIds.add(c.getCityId()));
        return cityIds;
    }

    public List<CitySnapshotDto> getComparedCities() {
        List<CitySnapshotDto> snapshots = new ArrayList<>();
        List<City> cities = cityRepository.findAll();
        
        for (City city : cities) {
            String cityId = city.getCityId();
            
            // Query SensorData for current Aqi and 30d trend
            List<SensorData> sensorData = sensorDataRepository.findAll();
            List<SensorData> citySensors = sensorData.stream().filter(s -> cityId.equals(s.getCityId())).toList();
            
            Integer currentAqi = null;
            Integer averageAqi30d = null;
            List<Integer> aqiTrend = new ArrayList<>();
            
            if (!citySensors.isEmpty()) {
                currentAqi = citySensors.stream()
                    .mapToInt(s -> s.getPollutants() != null && s.getPollutants().getAqi() != null ? s.getPollutants().getAqi() : 0)
                    .max().orElse(0);
                    
                averageAqi30d = (int) citySensors.stream()
                    .mapToInt(s -> s.getPollutants() != null && s.getPollutants().getAqi() != null ? s.getPollutants().getAqi() : 0)
                    .average().orElse(0);
                    
                aqiTrend = citySensors.stream()
                    .map(s -> s.getPollutants() != null && s.getPollutants().getAqi() != null ? s.getPollutants().getAqi() : 0)
                    .filter(aqi -> aqi > 0)
                    .limit(30).toList();
            }

            // Predictions
            List<Prediction> preds = predictionRepository.findAll();
            Double rmse = null;
            Double baselineRmse = null;
            var cityPreds = preds.stream().filter(p -> cityId.equals(p.getCityId())).toList();
            if (!cityPreds.isEmpty()) {
                Prediction lastPred = cityPreds.get(cityPreds.size() - 1);
                rmse = lastPred.getRmse();
                baselineRmse = lastPred.getBaselineRmse();
            }
            
            Integer forecastPeak = cityPreds.stream()
                    .flatMap(prediction -> prediction.getPredictions() != null ? prediction.getPredictions().stream() : java.util.stream.Stream.empty())
                    .map(Prediction.HourlyPrediction::getPredictedAqi)
                    .filter(value -> value != null && value > 0)
                    .max(Integer::compareTo)
                    .orElse(null);

            // Attributions
            String dominantSource = "UNKNOWN";
            
            // Enforcement
            List<EnforcementRecommendation> enforcements = enforcementRepository.findAll();
            int openEnf = (int) enforcements.stream().filter(e -> cityId.equals(e.getCityId()) && (e.getStatus() == EnforcementRecommendation.Status.OPEN || e.getStatus() == EnforcementRecommendation.Status.IN_PROGRESS)).count();
            int resolvedEnf = (int) enforcements.stream().filter(e -> cityId.equals(e.getCityId()) && e.getStatus() == EnforcementRecommendation.Status.RESOLVED).count();
            
            // Advisories
            List<Advisory> advisories = advisoryRepository.findAll();
            int advCount = (int) advisories.stream().filter(a -> cityId.equals(a.getCityId())).count();

            snapshots.add(CitySnapshotDto.builder()
                .cityId(cityId)
                .cityName(city.getCityName())
                .isSynthetic("SYNTHETIC_DEMO".equals(city.getDataSource()))
                .currentAqi(currentAqi)
                .averageAqi30d(averageAqi30d)
                .aqiTrend(aqiTrend)
                .forecastPeakAqi(forecastPeak)
                .dominantSource(dominantSource)
                .forecastRmse(rmse)
                .baselineRmse(baselineRmse)
                .openEnforcementCount(openEnf)
                .resolvedEnforcementCount(resolvedEnf)
                .avgResponseTimeHours(24.0)
                .advisoryCount(advCount)
                .build());
        }
        
        return snapshots;
    }
}
