package com.airsense.api.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeoJsonImportService {
    private final RealWorldDataProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public Optional<Map<String, Object>> load(String layerKey) {
        String path = properties.getGeojson().get(layerKey);
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        if (cache.containsKey(layerKey)) {
            return Optional.of(cache.get(layerKey));
        }
        try {
            File file = new File(path);
            if (!file.exists() || !file.isFile()) {
                log.warn("GeoJSON Import Failed layer={} reason=file_not_found path={}", layerKey, path);
                return Optional.empty();
            }
            Map<String, Object> geoJson = objectMapper.readValue(file, new TypeReference<>() {});
            if (!"FeatureCollection".equals(String.valueOf(geoJson.get("type")))) {
                log.warn("GeoJSON Import Failed layer={} reason=not_feature_collection path={}", layerKey, path);
                return Optional.empty();
            }
            Object features = geoJson.get("features");
            if (!(features instanceof List<?> list) || list.isEmpty()) {
                log.warn("GeoJSON Import Failed layer={} reason=no_features path={}", layerKey, path);
                return Optional.empty();
            }
            cache.put(layerKey, geoJson);
            log.info("GeoJSON Import Success layer={} features={} path={}", layerKey, list.size(), path);
            return Optional.of(geoJson);
        } catch (Exception e) {
            log.warn("GeoJSON Import Failed layer={} reason={} path={}", layerKey, e.getMessage(), path);
            return Optional.empty();
        }
    }
}
