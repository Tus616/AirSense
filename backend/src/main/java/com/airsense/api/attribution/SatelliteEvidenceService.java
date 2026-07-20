package com.airsense.api.attribution;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SatelliteEvidenceService {
    public Map<String, Object> normalize(Map<String, Object> satellite) {
        Map<String, Object> safe = satellite != null ? satellite : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        boolean available = Boolean.TRUE.equals(safe.get("available"));
        result.put("available", available);
        result.put("provider", safe.getOrDefault("provider", "GOOGLE_EARTH_ENGINE"));
        result.put("dataOrigin", available ? SourceScoringRule.DATA_ORIGIN : "UNAVAILABLE");
        result.put("observedAt", safe.getOrDefault("timestamp", Instant.now().toString()));
        result.put("measurementFallback", Boolean.TRUE.equals(safe.get("measurementFallback")));
        List<Map<String, Object>> products = new ArrayList<>();
        addProduct(products, "SENTINEL_5P_NO2", safe.get("no2"), "mol/m2", safe);
        addProduct(products, "SENTINEL_5P_SO2", safe.get("so2"), "mol/m2", safe);
        addProduct(products, "SENTINEL_5P_CO", safe.get("co"), "mol/m2", safe);
        addProduct(products, "SENTINEL_5P_O3", safe.get("o3"), "mol/m2", safe);
        addProduct(products, "AEROSOL_INDEX", safe.get("aerosolIndex"), "index", safe);
        addProduct(products, "MODIS_NDVI", safe.get("vegetationIndex"), "index", safe);
        addProduct(products, "MODIS_LST", safe.get("thermalAnomaly"), "C", safe);
        result.put("products", products);
        result.put("warnings", available ? List.of("SATELLITE_COLUMNS_ARE_SUPPORTING_SPATIAL_EVIDENCE_NOT_GROUND_CONCENTRATIONS")
                : List.of("SATELLITE_PROVIDER_UNAVAILABLE"));
        return result;
    }

    private void addProduct(List<Map<String, Object>> products, String product, Object value, String unit, Map<String, Object> source) {
        if (!(value instanceof Number)) return;
        products.add(Map.of(
                "product", product,
                "value", ((Number) value).doubleValue(),
                "unit", unit,
                "observedAt", source.getOrDefault("timestamp", Instant.now().toString()),
                "qualityStatus", Boolean.TRUE.equals(source.get("measurementFallback")) ? "MEASUREMENT_FALLBACK" : "VALID"
        ));
    }
}
