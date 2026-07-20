package com.airsense.api.fusion;

import com.airsense.api.services.EarthEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(30)
@RequiredArgsConstructor
public class FusionSatelliteProvider implements FusionDataProvider {
    private final EarthEngineService earthEngineService;
    private final FusionCityResolver cityResolver;

    @Override
    public String getProviderKey() {
        return "satellite";
    }

    @Override
    public String getProviderName() {
        return "Satellite";
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        Map<String, Object> data = earthEngineService.getSatelliteInsights(cityResolver.latitude(request), cityResolver.longitude(request));
        if (Boolean.TRUE.equals(data.get("available"))) {
            double confidence = Boolean.TRUE.equals(data.get("measurementFallback")) ? 0.65 : 0.85;
            return FusionProviderResponse.success(getProviderKey(), data, confidence);
        }
        return FusionProviderResponse.partial(getProviderKey(), data, 0.25, List.of(String.valueOf(data.getOrDefault("reason", "Satellite unavailable"))));
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setSatellite(response.getData());
    }
}
