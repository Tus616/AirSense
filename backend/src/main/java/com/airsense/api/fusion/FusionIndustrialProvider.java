package com.airsense.api.fusion;

import com.airsense.api.entities.IndustrialSource;
import com.airsense.api.repositories.IndustrialSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FusionIndustrialProvider implements FusionDataProvider {
    private final FusionCityResolver cityResolver;
    private final IndustrialSourceRepository industrialRepository;

    @Override
    public String getProviderKey() {
        return "industries";
    }

    @Override
    public String getProviderName() {
        return "Industrial";
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        var cityWards = cityResolver.wardIds(request.getCityId());
        List<IndustrialSource> sources = industrialRepository.findAll().stream()
                .filter(source -> cityWards.isEmpty() || cityWards.contains(source.getWardId()))
                .toList();
        List<Map<String, Object>> summaries = sources.stream().map(this::summary).toList();
        Map<String, Long> riskCounts = sources.stream()
                .map(IndustrialSource::getRiskLevel)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        Map<String, Object> data = Map.of(
                "count", summaries.size(),
                "riskCounts", riskCounts,
                "sources", summaries
        );
        if (sources.isEmpty()) {
            return FusionProviderResponse.partial(getProviderKey(), data, 0.30, List.of("No industrial sources found for city wards"));
        }
        return FusionProviderResponse.success(getProviderKey(), data, 0.80);
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setIndustries(listOfMaps(response.getData().get("sources")));
        context.getMetadata().put("industrialSummary", response.getData());
    }

    private Map<String, Object> summary(IndustrialSource source) {
        return Map.of(
                "facilityId", value(source.getFacilityId()),
                "facilityName", value(source.getFacilityName()),
                "category", value(source.getCategory()),
                "wardId", value(source.getWardId()),
                "emissionTypes", source.getEmissionTypes() != null ? source.getEmissionTypes() : List.of(),
                "riskLevel", value(source.getRiskLevel()),
                "complianceStatus", value(source.getComplianceStatus()),
                "coordinates", source.getLocation() != null
                        ? Map.of("longitude", source.getLocation().getX(), "latitude", source.getLocation().getY())
                        : Map.of()
        );
    }

    private String value(String value) {
        return value != null ? value : "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }
}
