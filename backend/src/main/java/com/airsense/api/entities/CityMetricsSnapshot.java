package com.airsense.api.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "city_metrics_snapshots")
@CompoundIndexes({
    @CompoundIndex(name = "city_time_idx", def = "{'cityId': 1, 'timestamp': -1}")
})
public class CityMetricsSnapshot {
    @Id
    private String id;
    private String cityId;
    private Instant timestamp;
    private int currentAqi;
    private int forecastPeakAqi;
    private String dominantSource;
    private Map<String, Double> sourceMix;
    private double forecastRmse;
    private double baselineRmse;
    private long openEnforcementCount;
    private long resolvedEnforcementCount;
    private double avgResponseTimeHours;
    private long advisoryCount;

    // Getters and Setters
    public CityMetricsSnapshot() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getCityId() { return cityId; }
    public void setCityId(String cityId) { this.cityId = cityId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public int getCurrentAqi() { return currentAqi; }
    public void setCurrentAqi(int currentAqi) { this.currentAqi = currentAqi; }

    public int getForecastPeakAqi() { return forecastPeakAqi; }
    public void setForecastPeakAqi(int forecastPeakAqi) { this.forecastPeakAqi = forecastPeakAqi; }

    public String getDominantSource() { return dominantSource; }
    public void setDominantSource(String dominantSource) { this.dominantSource = dominantSource; }

    public Map<String, Double> getSourceMix() { return sourceMix; }
    public void setSourceMix(Map<String, Double> sourceMix) { this.sourceMix = sourceMix; }

    public double getForecastRmse() { return forecastRmse; }
    public void setForecastRmse(double forecastRmse) { this.forecastRmse = forecastRmse; }

    public double getBaselineRmse() { return baselineRmse; }
    public void setBaselineRmse(double baselineRmse) { this.baselineRmse = baselineRmse; }

    public long getOpenEnforcementCount() { return openEnforcementCount; }
    public void setOpenEnforcementCount(long openEnforcementCount) { this.openEnforcementCount = openEnforcementCount; }

    public long getResolvedEnforcementCount() { return resolvedEnforcementCount; }
    public void setResolvedEnforcementCount(long resolvedEnforcementCount) { this.resolvedEnforcementCount = resolvedEnforcementCount; }

    public double getAvgResponseTimeHours() { return avgResponseTimeHours; }
    public void setAvgResponseTimeHours(double avgResponseTimeHours) { this.avgResponseTimeHours = avgResponseTimeHours; }

    public long getAdvisoryCount() { return advisoryCount; }
    public void setAdvisoryCount(long advisoryCount) { this.advisoryCount = advisoryCount; }
}
