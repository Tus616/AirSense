package com.airsense.api.providers;

import com.airsense.api.entities.SensorData;
import com.airsense.api.services.EarthEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SatelliteProvider {

    private static final Logger logger = LoggerFactory.getLogger(SatelliteProvider.class);
    private final EarthEngineService earthEngineService;

    public SatelliteProvider(EarthEngineService earthEngineService) {
        this.earthEngineService = earthEngineService;
    }

    public List<SensorData> enrichWithSatellite(List<SensorData> baseData) {
        logger.info("Enriching {} records with satellite data provider=Google Earth Engine", baseData.size());

        baseData.forEach(data -> {
            double lat = latitude(data);
            double lon = longitude(data);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                logger.warn("EarthEngine enrichment skipped sensorId={} reason=missing_coordinates", data.getSensorId());
                data.setSatellite(SensorData.Satellite.builder()
                        .no2Column(existingNo2(data))
                        .aerosolIndex(existingAerosolIndex(data))
                        .cloudFraction(existingCloudFraction(data))
                        .sourceFields(Map.of("available", false, "reason", "Missing sensor coordinates"))
                        .build());
                return;
            }
            Map<String, Object> insights = earthEngineService.getSatelliteInsights(lat, lon);

            data.setSatellite(SensorData.Satellite.builder()
                    .no2Column(numberOrDefault(insights.get("no2"), existingNo2(data)))
                    .aerosolIndex(numberOrDefault(insights.get("aerosolIndex"), existingAerosolIndex(data)))
                    .cloudFraction(existingCloudFraction(data))
                    .sourceFields(insights)
                    .build());

            if (Boolean.TRUE.equals(insights.get("available"))) {
                logger.info("EarthEngine enrichment success sensorId={} lat={} lon={}", data.getSensorId(), lat, lon);
            } else {
                logger.warn("EarthEngine enrichment fallback sensorId={} reason={}", data.getSensorId(), insights.get("reason"));
            }
        });

        return baseData;
    }

    private double longitude(SensorData data) {
        if (data.getCoordinates() != null && data.getCoordinates().getCoordinates() != null
                && !data.getCoordinates().getCoordinates().isEmpty()) {
            return data.getCoordinates().getCoordinates().get(0);
        }
        return Double.NaN;
    }

    private double latitude(SensorData data) {
        if (data.getCoordinates() != null && data.getCoordinates().getCoordinates() != null
                && data.getCoordinates().getCoordinates().size() > 1) {
            return data.getCoordinates().getCoordinates().get(1);
        }
        return Double.NaN;
    }

    private double numberOrDefault(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private double existingNo2(SensorData data) {
        return data.getSatellite() != null && data.getSatellite().getNo2Column() != null ? data.getSatellite().getNo2Column() : 0.0;
    }

    private double existingAerosolIndex(SensorData data) {
        return data.getSatellite() != null && data.getSatellite().getAerosolIndex() != null ? data.getSatellite().getAerosolIndex() : 0.0;
    }

    private double existingCloudFraction(SensorData data) {
        return data.getSatellite() != null && data.getSatellite().getCloudFraction() != null ? data.getSatellite().getCloudFraction() : 0.0;
    }
}
