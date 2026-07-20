package com.airsense.api.fusion;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "current-aqi")
public class CurrentAqiProperties {
    private Cpcb cpcb = new Cpcb();
    private Provider iqair = new Provider();
    private Provider openweather = new Provider();

    @Data
    public static class Cpcb {
        private long staleAfterMinutes = 180;
        private double maxStationDistanceKm = 50.0;
        private long requestTimeoutSeconds = 10;
        private int citySummaryMinStations = 2;
    }

    @Data
    public static class Provider {
        private long requestTimeoutSeconds = 10;
    }
}
