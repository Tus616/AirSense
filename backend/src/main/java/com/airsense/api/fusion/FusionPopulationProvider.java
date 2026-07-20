package com.airsense.api.fusion;

import com.airsense.api.repositories.VulnerabilityMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class FusionPopulationProvider implements FusionDataProvider {
    private final FusionCityResolver cityResolver;
    private final VulnerabilityMappingRepository vulnerabilityRepository;

    @Override
    public String getProviderKey() {
        return "population";
    }

    @Override
    public String getProviderName() {
        return "Population";
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        long cityPopulation = cityResolver.findCity(request.getCityId()).map(city -> city.getPopulation()).orElse(0L);
        var mappings = vulnerabilityRepository.findAll().stream()
                .filter(mapping -> request.getCityId().equalsIgnoreCase(mapping.getCityId()))
                .toList();
        Map<String, Object> data = Map.of(
                "population", cityPopulation,
                "wardProfileCount", mappings.size(),
                "elderlyPopulationDensity", mappings.stream().mapToDouble(mapping -> mapping.getElderlyPopulationDensity()).average().orElse(0.0),
                "childrenPopulationDensity", mappings.stream().mapToDouble(mapping -> mapping.getChildrenPopulationDensity()).average().orElse(0.0),
                "asthmaPrevalenceEstimate", mappings.stream().mapToDouble(mapping -> mapping.getAsthmaPrevalenceEstimate()).average().orElse(0.0),
                "hospitalsCount", mappings.stream().mapToInt(mapping -> mapping.getHospitalsCount()).sum(),
                "schoolsCount", mappings.stream().mapToInt(mapping -> mapping.getSchoolsCount()).sum()
        );
        double confidence = cityPopulation > 0 ? 0.80 : 0.35;
        return cityPopulation > 0
                ? FusionProviderResponse.success(getProviderKey(), data, confidence)
                : FusionProviderResponse.partial(getProviderKey(), data, confidence, java.util.List.of("City population not found"));
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setPopulation(response.getData());
    }
}
