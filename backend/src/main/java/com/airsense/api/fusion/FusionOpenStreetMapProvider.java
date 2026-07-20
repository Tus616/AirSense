package com.airsense.api.fusion;

import com.airsense.api.entities.City;
import com.airsense.api.repositories.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Order(10)
@RequiredArgsConstructor
public class FusionOpenStreetMapProvider implements FusionDataProvider {
    private final CityRepository cityRepository;
    private final FusionCityResolver cityResolver;

    @Override
    public String getProviderKey() {
        return "openStreetMap";
    }

    @Override
    public String getProviderName() {
        return "OpenStreetMap";
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        Map<String, Object> data = new HashMap<>();
        data.put("coordinates", cityResolver.coordinates(request));
        data.put("cityId", request.getCityId());
        cityRepository.findByCityId(request.getCityId()).ifPresent(city -> copyCity(data, city));
        if (request.getPlaceId() != null && !request.getPlaceId().isBlank()) data.put("placeId", request.getPlaceId());
        if (request.getCityName() != null && !request.getCityName().isBlank()) data.put("cityName", request.getCityName());
        if (request.getState() != null && !request.getState().isBlank()) data.put("state", request.getState());
        if (request.getCountry() != null && !request.getCountry().isBlank()) data.put("country", request.getCountry());
        double confidence = data.containsKey("cityName") ? 0.90 : 0.45;
        return FusionProviderResponse.success(getProviderKey(), data, confidence);
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        Map<String, Object> data = response.getData();
        context.setCityId(String.valueOf(data.getOrDefault("cityId", context.getCityId())));
        context.setCity(String.valueOf(data.getOrDefault("cityName", context.getCityId())));
        context.setCoordinates(asMap(data.get("coordinates")));
        context.getMetadata().put("location", data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private void copyCity(Map<String, Object> data, City city) {
        data.put("cityName", city.getCityName());
        data.put("state", city.getState());
        data.put("country", city.getCountry());
        data.put("timezone", city.getTimezone());
        data.put("population", city.getPopulation());
        data.put("boundingBox", city.getBoundingBox() != null ? city.getBoundingBox() : Map.of());
        data.put("dataSource", city.getDataSource());
    }
}
