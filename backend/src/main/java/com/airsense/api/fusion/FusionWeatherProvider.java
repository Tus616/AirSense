package com.airsense.api.fusion;

import com.airsense.api.services.OpenWeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(20)
@RequiredArgsConstructor
public class FusionWeatherProvider implements FusionDataProvider {
    private final OpenWeatherService openWeatherService;
    private final FusionCityResolver cityResolver;

    @Override
    public String getProviderKey() {
        return "weather";
    }

    @Override
    public String getProviderName() {
        return "Weather";
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        Map<String, Object> data = openWeatherService.getCurrentWeather(cityResolver.latitude(request), cityResolver.longitude(request));
        if (Boolean.TRUE.equals(data.get("available"))) {
            return FusionProviderResponse.success(getProviderKey(), data, 0.90);
        }
        return FusionProviderResponse.partial(getProviderKey(), data, 0.30, List.of(String.valueOf(data.getOrDefault("reason", "Weather unavailable"))));
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setWeather(response.getData());
        context.setTemperature(number(response.getData().get("temperature")));
        context.setHumidity(number(response.getData().get("humidity")));
        context.setWind(Map.of(
                "speed", response.getData().getOrDefault("windSpeed", 0.0),
                "direction", response.getData().getOrDefault("windDirection", 0.0)
        ));
    }

    private double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }
}
