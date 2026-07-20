package com.airsense.api.attribution;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "attribution")
public class SourceAttributionProperties {
    private boolean enabled = true;
    private String engineVersion = "source-attribution-v1";
    private double localRadiusKm = 5.0;
    private double regionalRadiusKm = 50.0;
    private double fireRadiusKm = 300.0;
    private int geospatialCacheMinutes = 1440;
    private int fireCacheMinutes = 60;
    private int satelliteCacheMinutes = 360;
    private int resultCacheMinutes = 30;
    private int httpTimeoutSeconds = 15;
    private int maxRetries = 2;
    private String nasaFirmsApiKey = "";
    private String nasaFirmsBaseUrl = "https://firms.modaps.eosdis.nasa.gov/api/area/csv";

    private double elevatedNo2 = 40.0;
    private double elevatedSo2 = 20.0;
    private double elevatedCo = 1.0;
    private double elevatedO3 = 80.0;
    private double pm10DominanceRatio = 1.35;
    private double dryHumidityPercent = 40.0;
    private double rainfallWashoutMm = 0.5;
    private double lowWindMps = 2.0;
    private double strongWindMps = 5.0;
    private double highRoadDensityKmPerSqKm = 1.0;
    private double nearMajorRoadKm = 0.5;
    private double staleEvidenceHours = 24.0;
    private double minimumKnownBudget = 15.0;
    private double trafficNo2Weight = 0.82;
    private double trafficCoWeight = 0.35;
    private double trafficRoadDensityWeight = 0.28;
    private double trafficNearestRoadWeight = 0.16;
    private double trafficRushHourWeight = 0.08;
    private double trafficLowWindWeight = 0.12;
    private double trafficLowNo2Penalty = 0.28;
    private double trafficLowRoadDensityPenalty = 0.20;
    private double trafficRainPenalty = 0.18;
    private double trafficProxyOnlyMaxScore = 0.55;
    private int proxyOnlyUnknownFloorPercent = 38;
    private int missingCriticalEvidenceUnknownBoostPercent = 4;
}
