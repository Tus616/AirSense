package com.airsense.api.services;

import com.airsense.api.dto.*;
import com.airsense.api.entities.SensorData;
import com.airsense.api.entities.GridForecast;
import com.airsense.api.repositories.GridForecastRepository;
import com.airsense.api.repositories.PredictionRepository;
import com.airsense.api.repositories.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class GovDataService {
    @Autowired
    private SensorDataRepository sensorDataRepository;

    @Autowired
    private GridForecastRepository gridForecastRepository;

    @Autowired
    private PredictionRepository predictionRepository;

    @Autowired
    private ForecastEvaluationService forecastEvaluationService;

    public List<SensorStationDto> getStations() {
        return getStations("UNKNOWN_PLACE");
    }

    public List<SensorStationDto> getStations(String cityId) {
        List<SensorData> recentData = getLatestDataPerStation(cityId);
        
        if (recentData.isEmpty()) {
            return List.of();
        }

        return recentData.stream().map(data -> {
            String status = "online";
            if (data.getQualityFlags() != null && !data.getQualityFlags().getMissingFields().isEmpty()) {
                status = "maintenance";
            }
            if (data.getTimestamp().isBefore(Instant.now().minus(48, ChronoUnit.HOURS))) {
                status = "offline";
            }

            int aqi = data.getPollutants() != null && data.getPollutants().getAqi() != null ? data.getPollutants().getAqi() : 0;
            double pm25 = data.getPollutants() != null && data.getPollutants().getPm25() != null ? data.getPollutants().getPm25() : 0.0;
            double pm10 = data.getPollutants() != null && data.getPollutants().getPm10() != null ? data.getPollutants().getPm10() : 0.0;
            double no2 = data.getPollutants() != null && data.getPollutants().getNo2() != null ? data.getPollutants().getNo2() : 0.0;
            double so2 = data.getPollutants() != null && data.getPollutants().getSo2() != null ? data.getPollutants().getSo2() : 0.0;
            double co = data.getPollutants() != null && data.getPollutants().getCo() != null ? data.getPollutants().getCo() : 0.0;
            int o3 = data.getPollutants() != null && data.getPollutants().getO3() != null ? data.getPollutants().getO3().intValue() : 0;

            PollutantsDto pol = new PollutantsDto((int)pm25, (int)pm10, (int)no2, (int)so2, co, o3);

            double lat = data.getCoordinates() != null && data.getCoordinates().getCoordinates().size() > 1 ? data.getCoordinates().getCoordinates().get(1) : 0.0;
            double lng = data.getCoordinates() != null && data.getCoordinates().getCoordinates().size() > 0 ? data.getCoordinates().getCoordinates().get(0) : 0.0;

            return new SensorStationDto(data.getSensorId(), data.getStationName(), lat, lng, aqi, status, pol);
        }).collect(Collectors.toList());
    }

    private List<SensorData> getLatestDataPerStation() {
        return getLatestDataPerStation(null);
    }

    private List<SensorData> getLatestDataPerStation(String cityId) {
        Instant yesterday = Instant.now().minus(48, ChronoUnit.HOURS);
        List<SensorData> recent = sensorDataRepository.findByTimestampBetween(yesterday, Instant.now());
        if (cityId != null && !cityId.isBlank()) {
            recent = recent.stream()
                    .filter(data -> cityId.equalsIgnoreCase(data.getCityId()))
                    .collect(Collectors.toList());
        }
        
        return recent.stream()
                .collect(Collectors.groupingBy(SensorData::getSensorId,
                        Collectors.maxBy(Comparator.comparing(SensorData::getTimestamp))))
                .values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    public List<HistoricalTrendDto> getTrends() {
        // Fallback placeholder (no historical daily agg yet)
        return Arrays.asList(
            new HistoricalTrendDto("2026-06-07", 145, 82, 140, 42, 55),
            new HistoricalTrendDto("2026-06-08", 158, 94, 155, 48, 50),
            new HistoricalTrendDto("2026-06-09", 132, 76, 128, 38, 58),
            new HistoricalTrendDto("2026-06-10", 168, 102, 165, 55, 45),
            new HistoricalTrendDto("2026-06-11", 175, 108, 172, 58, 42)
        );
    }

    public List<SensorReliabilityDto> getReliability() {
        // Fallback placeholder
        return Arrays.asList(
            new SensorReliabilityDto("CPCB-DL01", "ITO", 98.2, 96.5, "2026-06-15"),
            new SensorReliabilityDto("CPCB-DL02", "Anand Vihar", 97.5, 95.8, "2026-06-10")
        );
    }

    public List<SourceAttributionDto> getSourceAttribution() {
        // Fallback placeholder
        return Arrays.asList(
            new SourceAttributionDto("Vehicular Emissions", 34),
            new SourceAttributionDto("Industrial", 22),
            new SourceAttributionDto("Construction Dust", 16),
            new SourceAttributionDto("Biomass Burning", 12),
            new SourceAttributionDto("Power Plants", 8)
        );
    }

    public List<List<Double>> getHeatmap() {
        return getHeatmap("UNKNOWN_PLACE");
    }

    public List<List<Double>> getHeatmap(String cityId) {
        List<SensorData> recentData = getLatestDataPerStation(cityId);
        
        if (recentData.isEmpty()) {
            return List.of();
        }

        return recentData.stream().map(data -> {
            double lat = data.getCoordinates() != null && data.getCoordinates().getCoordinates().size() > 1 ? data.getCoordinates().getCoordinates().get(1) : 0.0;
            double lng = data.getCoordinates() != null && data.getCoordinates().getCoordinates().size() > 0 ? data.getCoordinates().getCoordinates().get(0) : 0.0;
            int aqi = data.getPollutants() != null && data.getPollutants().getAqi() != null ? data.getPollutants().getAqi() : 0;
            double intensity = Math.min(aqi / 500.0, 1.0); // Normalize 0-500 to 0.0-1.0
            return Arrays.asList(lat, lng, intensity);
        }).collect(Collectors.toList());
    }

    public List<IndustrialPolluterDto> getPolluters() {
        return Arrays.asList(
            new IndustrialPolluterDto("ip-01", "Bawana Industrial Area Unit-A", 28.7932, 77.0515, "Manufacturing", "non-compliant", "critical"),
            new IndustrialPolluterDto("ip-02", "Narela Metal Works", 28.8480, 77.1020, "Metal Processing", "non-compliant", "high"),
            new IndustrialPolluterDto("ip-03", "Okhla Waste-to-Energy Plant", 28.5310, 77.2710, "Waste Processing", "under-review", "high"),
            new IndustrialPolluterDto("ip-04", "Wazirpur Industrial Cluster", 28.6970, 77.1630, "Manufacturing", "non-compliant", "critical"),
            new IndustrialPolluterDto("ip-05", "Mundka Chemical Plant", 28.6850, 77.0250, "Chemicals", "compliant", "medium"),
            new IndustrialPolluterDto("ip-06", "GT Karnal Road Dyeing Unit", 28.7420, 77.1380, "Textiles", "under-review", "medium"),
            new IndustrialPolluterDto("ip-07", "Tikri Border Brick Kilns", 28.6650, 76.9680, "Construction", "non-compliant", "high")
        );
    }

    public PlumeFeatureCollectionDto getPlume() {
        PlumeFeatureCollectionDto.PlumeFeatureDto feature1 = PlumeFeatureCollectionDto.PlumeFeatureDto.builder()
                .type("Feature")
                .properties(new PlumeFeatureCollectionDto.PlumePropertiesDto("PM2.5", "high", 24))
                .geometry(new PlumeFeatureCollectionDto.PlumeGeometryDto("Polygon", Collections.singletonList(Arrays.asList(
                        Arrays.asList(77.05, 28.68), Arrays.asList(77.12, 28.72), Arrays.asList(77.18, 28.74),
                        Arrays.asList(77.25, 28.72), Arrays.asList(77.28, 28.68), Arrays.asList(77.26, 28.64),
                        Arrays.asList(77.20, 28.60), Arrays.asList(77.14, 28.58), Arrays.asList(77.08, 28.60),
                        Arrays.asList(77.04, 28.64), Arrays.asList(77.05, 28.68)
                ))))
                .build();

        PlumeFeatureCollectionDto.PlumeFeatureDto feature2 = PlumeFeatureCollectionDto.PlumeFeatureDto.builder()
                .type("Feature")
                .properties(new PlumeFeatureCollectionDto.PlumePropertiesDto("PM2.5", "moderate", 48))
                .geometry(new PlumeFeatureCollectionDto.PlumeGeometryDto("Polygon", Collections.singletonList(Arrays.asList(
                        Arrays.asList(77.00, 28.70), Arrays.asList(77.10, 28.76), Arrays.asList(77.20, 28.78),
                        Arrays.asList(77.30, 28.76), Arrays.asList(77.34, 28.70), Arrays.asList(77.32, 28.62),
                        Arrays.asList(77.24, 28.56), Arrays.asList(77.14, 28.54), Arrays.asList(77.06, 28.56),
                        Arrays.asList(76.98, 28.62), Arrays.asList(77.00, 28.70)
                ))))
                .build();

        return PlumeFeatureCollectionDto.builder()
                .type("FeatureCollection")
                .features(Arrays.asList(feature1, feature2))
                .build();
    }

    public Object getGridForecast(String cityId) {
        // Return latest grids for the city
        // Simplified: just return all for demo (in reality, filter by latest generatedAt)
        return gridForecastRepository.findByCityIdOrderByGeneratedAtDesc(cityId).stream()
            // To get distinct gridIds (as there might be older generations)
            .collect(Collectors.groupingBy(GridForecast::getGridId,
                    Collectors.maxBy(Comparator.comparing(GridForecast::getGeneratedAt))))
            .values().stream()
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(grid -> java.util.Map.of(
                "gridId", grid.getGridId(),
                "location", grid.getLocation() != null ? grid.getLocation() : new double[0],
                "predictions", grid.getPredictions()
            ))
            .collect(Collectors.toList());
    }

    public Object getForecastMetrics() {
        var metrics = forecastEvaluationService.evaluate();
        return java.util.Map.of(
            "rmse_aqi", metrics.getModelRmse(),
            "mae_aqi", metrics.getModelMae(),
            "baseline_rmse_aqi", metrics.getPersistenceRmse(),
            "baseline_mae_aqi", metrics.getPersistenceMae(),
            "improvement_pct_aqi", metrics.getImprovementPct(),
            "evaluated_horizons", metrics.getEvaluatedCellHours()
        );
    }
}
