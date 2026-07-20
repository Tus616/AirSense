package com.airsense.api.fusion;

import com.airsense.api.entities.SensorData;
import com.airsense.api.data.OpenStreetMapDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FusionTrafficProvider implements FusionDataProvider {
    private final FusionCityResolver cityResolver;
    private final OpenStreetMapDataService openStreetMapDataService;

    @Override
    public String getProviderKey() {
        return "traffic";
    }

    @Override
    public String getProviderName() {
        return "Traffic";
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        List<SensorData.Traffic> traffic = cityResolver.recentSensorData(request.getCityId(), 48).stream()
                .map(SensorData::getTraffic)
                .filter(value -> value != null)
                .toList();
        Map<String, Object> data = new HashMap<>();
        data.put("sampleCount", traffic.size());
        data.put("averageCongestionIndex", traffic.stream().mapToDouble(v -> value(v.getCongestionIndex())).average().orElse(0.0));
        data.put("averageSpeed", traffic.stream().mapToDouble(v -> value(v.getAverageSpeed())).average().orElse(0.0));
        data.put("averageFlow", traffic.stream().mapToDouble(v -> value(v.getFlow())).average().orElse(0.0));
        openStreetMapDataService.fetch(cityResolver.latitude(request), cityResolver.longitude(request)).ifPresent(osm -> {
            List<Map<String, Object>> roads = openStreetMapDataService.features(osm, "roads");
            data.put("roadGeometrySource", "openstreetmap");
            data.put("roadSegmentCount", roads.size());
            data.put("roadClassCounts", roadClassCounts(roads));
        });
        if (traffic.isEmpty() && ((Number) data.getOrDefault("roadSegmentCount", 0)).intValue() == 0) {
            return FusionProviderResponse.partial(getProviderKey(), data, 0.25, List.of("No recent traffic enrichments or OSM road geometry found"));
        }
        return FusionProviderResponse.success(getProviderKey(), data, traffic.isEmpty() ? 0.55 : 0.75);
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setTraffic(response.getData());
    }

    private double value(Double value) {
        return value != null ? value : 0.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> roadClassCounts(List<Map<String, Object>> roads) {
        return roads.stream()
                .map(feature -> feature.get("properties") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.<String, Object>of())
                .map(properties -> properties.get("osmTags") instanceof Map<?, ?> tags ? (Map<String, Object>) tags : Map.<String, Object>of())
                .map(tags -> String.valueOf(tags.getOrDefault("highway", "unknown")))
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }
}
