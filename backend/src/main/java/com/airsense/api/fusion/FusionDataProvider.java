package com.airsense.api.fusion;

import java.time.Duration;

public interface FusionDataProvider {
    String getProviderKey();

    default String getProviderName() {
        return getProviderKey();
    }

    default boolean isCacheable() {
        return false;
    }

    default Duration getCacheTtl(FusionCacheProperties cacheProperties) {
        return cacheProperties.ttlFor(getProviderKey());
    }

    FusionProviderResponse fetch(FusionRequest request);

    void contribute(CityEnvironmentalContext context, FusionProviderResponse response);
}
