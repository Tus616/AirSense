package com.airsense.api.fusion;

import com.airsense.api.entities.SensorData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FusionLandUseProvider implements FusionDataProvider {
    private final FusionCityResolver cityResolver;

    @Override
    public String getProviderKey() {
        return "landUse";
    }

    @Override
    public String getProviderName() {
        return "Land Use";
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        List<SensorData.LandUse> landUses = cityResolver.recentSensorData(request.getCityId(), 24 * 30L).stream()
                .map(SensorData::getLandUse)
                .filter(value -> value != null)
                .toList();
        Map<String, Long> typeCounts = landUses.stream()
                .map(SensorData.LandUse::getPrimaryType)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        Map<String, Object> data = Map.of(
                "sampleCount", landUses.size(),
                "primaryTypeCounts", typeCounts,
                "dominantType", typeCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("")
        );
        if (landUses.isEmpty()) {
            return FusionProviderResponse.partial(getProviderKey(), data, 0.25, List.of("No land-use enrichments found"));
        }
        return FusionProviderResponse.success(getProviderKey(), data, 0.70);
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setLandUse(response.getData());
    }
}
