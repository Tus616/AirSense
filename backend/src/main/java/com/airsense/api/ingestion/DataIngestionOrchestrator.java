package com.airsense.api.ingestion;

import com.airsense.api.entities.SensorData;
import com.airsense.api.repositories.SensorDataRepository;
import com.airsense.api.cloud.CloudExportService;
import com.airsense.api.preprocessing.DataNormalizer;
import com.airsense.api.preprocessing.QualityControlService;
import com.airsense.api.providers.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DataIngestionOrchestrator {
    
    private static final Logger logger = LoggerFactory.getLogger(DataIngestionOrchestrator.class);

    @Autowired
    private AirQualityProvider airQualityProvider;

    @Autowired
    private WeatherProvider weatherProvider;

    @Autowired
    private TrafficProvider trafficProvider;

    @Autowired
    private SatelliteProvider satelliteProvider;

    @Autowired
    private LandUseProvider landUseProvider;

    @Autowired
    private DataNormalizer dataNormalizer;

    @Autowired
    private QualityControlService qualityControlService;

    @Autowired
    private SensorDataRepository sensorDataRepository;
    
    @Autowired
    private CloudExportService cloudExportService;

    // Run hourly
    @Scheduled(cron = "0 0 * * * *")
    public void runHourlyIngestion() {
        runIngestion("all");
    }

    public void runIngestion(String source) {
        logger.info("Starting data ingestion pipeline for source: {}", source);
        Instant now = Instant.now();

        try {
            // 1. Fetch Raw Data (Air Quality is the base)
            List<SensorData> baseData = airQualityProvider.fetchLatestData();
            
            // 2. Fetch ancillary data and merge
            if ("all".equalsIgnoreCase(source) || "weather".equalsIgnoreCase(source)) {
                baseData = weatherProvider.enrichWithWeather(baseData);
            }
            if ("all".equalsIgnoreCase(source) || "traffic".equalsIgnoreCase(source)) {
                baseData = trafficProvider.enrichWithTraffic(baseData);
            }
            if ("all".equalsIgnoreCase(source) || "satellite".equalsIgnoreCase(source)) {
                baseData = satelliteProvider.enrichWithSatellite(baseData);
            }
            if ("all".equalsIgnoreCase(source) || "landuse".equalsIgnoreCase(source)) {
                baseData = landUseProvider.enrichWithLandUse(baseData);
            }

            // 3. Normalize & QC
            List<SensorData> processedData = baseData.stream()
                .map(dataNormalizer::normalize)
                .map(qualityControlService::applyQualityControl)
                .collect(Collectors.toList());

            // 4. Save to MongoDB
            sensorDataRepository.saveAll(processedData);
            logger.info("Successfully ingested {} records into MongoDB", processedData.size());

            // 5. Export to Cloud Data Lake
            cloudExportService.exportRawData(processedData);
            cloudExportService.exportProcessedToBigQuery(processedData);
            
        } catch (Exception e) {
            logger.error("Data ingestion pipeline failed: ", e);
        }
    }
}
