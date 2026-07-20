package com.airsense.api.temporal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineResult {
    private String city;
    private String cityId;
    private Instant generatedAt;
    private String cacheStatus;
    private long cacheTtlSeconds;
    @Builder.Default
    private List<TimelineFrame> frames = new ArrayList<>();
    @Builder.Default
    private List<TimelineEvent> events = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
