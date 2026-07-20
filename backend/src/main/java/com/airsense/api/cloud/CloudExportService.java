package com.airsense.api.cloud;

import com.airsense.api.entities.SensorData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CloudExportService {

    private static final Logger logger = LoggerFactory.getLogger(CloudExportService.class);

    @Value("${gcp.bucket:}")
    private String gcpBucket;

    @Value("${bigquery.dataset:}")
    private String bqDataset;

    public void exportRawData(List<SensorData> data) {
        if (gcpBucket == null || gcpBucket.isEmpty()) {
            logger.warn("Skipping Raw Data export to Cloud Storage (GCP_BUCKET not configured)");
            return;
        }
        logger.info("Exporting {} raw records to bucket: {}", data.size(), gcpBucket);
        // Implementation for Dataplex landing zone goes here
    }

    public void exportProcessedToBigQuery(List<SensorData> data) {
        if (bqDataset == null || bqDataset.isEmpty()) {
            logger.warn("Skipping Processed Data export to BigQuery (BIGQUERY_DATASET not configured)");
            return;
        }
        logger.info("Exporting {} processed records to BigQuery dataset: {}", data.size(), bqDataset);
        // Implementation for BigQuery export goes here
    }
}
