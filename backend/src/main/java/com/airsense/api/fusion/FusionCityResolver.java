package com.airsense.api.fusion;

import com.airsense.api.entities.City;
import com.airsense.api.entities.SensorData;
import com.airsense.api.repositories.CityRepository;
import com.airsense.api.repositories.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FusionCityResolver {
    private final CityRepository cityRepository;
    private final SensorDataRepository sensorDataRepository;

    public Optional<City> findCity(String cityId) {
        if (cityId == null || cityId.isBlank() || "UNKNOWN_PLACE".equalsIgnoreCase(cityId)) {
            return Optional.empty();
        }
        return cityRepository.findByCityId(cityId);
    }

    public String cityName(String cityId) {
        return findCity(cityId).map(City::getCityName).orElse(cityId);
    }

    public double latitude(FusionRequest request) {
        if (request.getLatitude() != null) {
            return request.getLatitude();
        }
        return findCity(request.getCityId())
                .map(City::getCenter)
                .map(GeoJsonPoint::getY)
                .orElse(Double.NaN);
    }

    public double longitude(FusionRequest request) {
        if (request.getLongitude() != null) {
            return request.getLongitude();
        }
        return findCity(request.getCityId())
                .map(City::getCenter)
                .map(GeoJsonPoint::getX)
                .orElse(Double.NaN);
    }

    public Map<String, Object> coordinates(FusionRequest request) {
        double lat = latitude(request);
        double lon = longitude(request);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return Map.of();
        }
        return Map.of("latitude", lat, "longitude", lon);
    }

    public List<SensorData> recentSensorData(String cityId, long hours) {
        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS);
        if (cityId == null || cityId.isBlank() || "UNKNOWN_PLACE".equalsIgnoreCase(cityId)) {
            return List.of();
        }
        return sensorDataRepository.findByTimestampBetween(from, Instant.now()).stream()
                .filter(data -> cityId.equalsIgnoreCase(data.getCityId()))
                .collect(Collectors.toList());
    }

    public Set<String> wardIds(String cityId) {
        Set<String> wards = new HashSet<>();
        if (cityId == null || cityId.isBlank() || "UNKNOWN_PLACE".equalsIgnoreCase(cityId)) {
            return wards;
        }
        findCity(cityId).map(City::getDefaultWards).ifPresent(wards::addAll);
        recentSensorData(cityId, 24 * 45L).stream()
                .map(SensorData::getWardId)
                .filter(ward -> ward != null && !ward.isBlank())
                .forEach(wards::add);
        return wards;
    }
}
