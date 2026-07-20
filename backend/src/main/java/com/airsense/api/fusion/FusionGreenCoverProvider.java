package com.airsense.api.fusion;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FusionGreenCoverProvider implements FusionDataProvider {

    @Override
    public String getProviderKey() {
        return "greenCover";
    }

    @Override
    public String getProviderName() {
        return "Green Cover";
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    @Override
    public FusionProviderResponse fetch(FusionRequest request) {
        Map<String, Object> data = Map.of(
                "greenCoverIndex", 0.0,
                "vegetationIndex", 0.0,
                "source", "not_configured"
        );
        return FusionProviderResponse.partial(
                getProviderKey(),
                data,
                0.20,
                List.of("No dedicated green-cover provider is configured yet")
        );
    }

    @Override
    public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
        context.setGreenCover(response.getData());
    }
}
