package com.airsense.api.fusion;

import com.airsense.api.entities.ConstructionPermit;
import com.airsense.api.repositories.ConstructionPermitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FusionConstructionProvider implements FusionDataProvider {
    private final FusionCityResolver cityResolver;
    private final ConstructionPermitRepository constructionRepository;

    @Override
    public String getProviderKey() {
        return "construction";
    }

    @Override
    public String getProviderName() {
        return "Construction";
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        var cityWards = cityResolver.wardIds(request.getCityId());
        List<ConstructionPermit> permits = constructionRepository.findAll().stream()
                .filter(permit -> cityWards.isEmpty() || cityWards.contains(permit.getWardId()))
                .toList();
        List<Map<String, Object>> summaries = permits.stream().map(this::summary).toList();
        Map<String, Object> data = Map.of(
                "count", summaries.size(),
                "activeCount", permits.stream().filter(this::isActive).count(),
                "permits", summaries
        );
        if (permits.isEmpty()) {
            return FusionProviderResponse.partial(getProviderKey(), data, 0.30, List.of("No construction permits found for city wards"));
        }
        return FusionProviderResponse.success(getProviderKey(), data, 0.78);
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setConstruction(listOfMaps(response.getData().get("permits")));
        context.getMetadata().put("constructionSummary", response.getData());
    }

    private Map<String, Object> summary(ConstructionPermit permit) {
        return Map.of(
                "permitId", value(permit.getPermitId()),
                "wardId", value(permit.getWardId()),
                "projectType", value(permit.getProjectType()),
                "contractor", value(permit.getContractor()),
                "dustRiskLevel", value(permit.getDustRiskLevel()),
                "status", value(permit.getStatus()),
                "activeFrom", permit.getActiveFrom() != null ? permit.getActiveFrom().toString() : "",
                "activeTo", permit.getActiveTo() != null ? permit.getActiveTo().toString() : ""
        );
    }

    private boolean isActive(ConstructionPermit permit) {
        Instant now = Instant.now();
        boolean dateActive = (permit.getActiveFrom() == null || !permit.getActiveFrom().isAfter(now))
                && (permit.getActiveTo() == null || !permit.getActiveTo().isBefore(now));
        return dateActive && !"SUSPENDED".equalsIgnoreCase(permit.getStatus());
    }

    private String value(String value) {
        return value != null ? value : "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }
}
