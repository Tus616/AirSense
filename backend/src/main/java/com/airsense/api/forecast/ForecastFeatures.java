package com.airsense.api.forecast;

import com.airsense.api.attribution.PollutionSourceType;
import lombok.Builder;
import lombok.Data;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class ForecastFeatures {
    private String city;
    private String cityId;
    private String wardId;
    private String sensorId;
    private Instant contextTimestamp;
    private double currentAqi;
    private int historyCount;
    private double historicalAqiAverage;
    private double historicalTrend;
    private double pm25;
    private double pm10;
    private double no2;
    private double so2;
    private double co;
    private double temperature;
    private double humidity;
    private double pressure;
    private double windSpeed;
    private double windDirection;
    private double rainfall;
    private double rainProbability;
    private boolean satelliteThermalAnomaly;
    private double trafficCongestion;
    private double trafficSpeed;
    private int constructionCount;
    private int highDustConstructionCount;
    private int industrialCount;
    private int highRiskIndustrialCount;
    private double greenCoverIndex;
    private double populationDensity;
    private String season;
    private int hourOfDay;
    private DayOfWeek dayOfWeek;
    private double inputCompleteness;
    private double providerConfidence;
    private double dataFreshness;
    private PollutionSourceType attributedDominantSource;
    @Builder.Default
    private List<String> availableDatasets = new ArrayList<>();
}
