package com.airsense.api.history;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "historical")
public class HistoricalAqiProperties {
    private boolean ingestionEnabled = true;
    private long ingestionIntervalMinutes = 60;
    private long retentionDays = 365;
    private int locationRefreshLimit = 100;
    private long minRequestGapMs = 500;
    private long staleAfterMinutes = 180;
    private long providerTimestampFutureToleranceMinutes = 360;
}
