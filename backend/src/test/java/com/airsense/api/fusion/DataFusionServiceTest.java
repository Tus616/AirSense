package com.airsense.api.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DataFusionServiceTest {

    @Test
    void buildContextKeepsRemainingProvidersWhenOneFails() {
        FusionCacheProperties properties = new FusionCacheProperties();
        DataFusionService service = new DataFusionService(
                List.of(new WeatherFakeProvider(), new FailingFakeProvider()),
                new FusionProviderCache(),
                properties
        );

        CityEnvironmentalContext context = service.buildCityContext("DELHI");

        assertThat(context.getWeather()).containsEntry("temperature", 31.5);
        assertThat(context.getProviderStatus()).containsEntry("weather", FusionStatus.SUCCESS.name());
        assertThat(context.getProviderStatus()).containsEntry("broken", FusionStatus.FAILED.name());
        assertThat(context.getMetadata()).containsKey("brokenErrors");
    }

    @Test
    void cacheableProviderIsReusedWithinTtl() {
        FusionCacheProperties properties = new FusionCacheProperties();
        properties.setDefaultTtlSeconds(60);
        CountingCacheableProvider provider = new CountingCacheableProvider();
        DataFusionService service = new DataFusionService(
                List.of(provider),
                new FusionProviderCache(),
                properties
        );

        CityEnvironmentalContext first = service.buildCityContext("DELHI");
        CityEnvironmentalContext second = service.buildContext(FusionRequest.builder()
                .cityId("DELHI")
                .parameters(Map.of("refresh", true))
                .build());

        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(first.getAqi()).containsEntry("currentAqi", 101);
        assertThat(second.getAqi()).containsEntry("currentAqi", 101);
        assertThat(second.getMetadata()).containsEntry("aqiCached", true);
    }

    @Test
    void fusedSnapshotIsReusedForSameLocationWithinTtl() {
        ChangingObservedAtProvider provider = new ChangingObservedAtProvider();
        DataFusionService service = new DataFusionService(
                List.of(provider),
                new FusionProviderCache(),
                new FusionCacheProperties()
        );
        FusionRequest request = FusionRequest.builder()
                .cityId("DELHI")
                .latitude(28.6139)
                .longitude(77.2090)
                .build();

        CityEnvironmentalContext first = service.buildContext(request);
        CityEnvironmentalContext second = service.buildContext(request);

        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(second.getMetadata()).containsEntry("snapshotReused", true);
        assertThat(second.getMetadata().get("snapshotId")).isEqualTo(first.getMetadata().get("snapshotId"));
        assertThat(second.getMetadata()).containsKey("snapshotCacheKey");
    }

    @Test
    void refreshBypassesFusedSnapshotReuse() {
        ChangingObservedAtProvider provider = new ChangingObservedAtProvider();
        DataFusionService service = new DataFusionService(
                List.of(provider),
                new FusionProviderCache(),
                new FusionCacheProperties()
        );
        FusionRequest request = FusionRequest.builder()
                .cityId("DELHI")
                .latitude(28.6139)
                .longitude(77.2090)
                .build();
        FusionRequest refresh = FusionRequest.builder()
                .cityId("DELHI")
                .latitude(28.6139)
                .longitude(77.2090)
                .parameters(Map.of("refresh", true))
                .build();

        CityEnvironmentalContext first = service.buildContext(request);
        CityEnvironmentalContext second = service.buildContext(refresh);

        assertThat(provider.calls.get()).isEqualTo(2);
        assertThat(second.getMetadata()).containsEntry("snapshotReused", false);
        assertThat(second.getMetadata().get("snapshotId")).isNotEqualTo(first.getMetadata().get("snapshotId"));
    }

    private static class WeatherFakeProvider implements FusionDataProvider {
        @Override
        public String getProviderKey() {
            return "weather";
        }

        @Override
        public FusionProviderResponse fetch(FusionRequest request) {
            return FusionProviderResponse.success(getProviderKey(), Map.of("temperature", 31.5), 0.9);
        }

        @Override
        public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
            context.setWeather(response.getData());
        }
    }

    private static class FailingFakeProvider implements FusionDataProvider {
        @Override
        public String getProviderKey() {
            return "broken";
        }

        @Override
        public FusionProviderResponse fetch(FusionRequest request) {
            throw new IllegalStateException("provider exploded");
        }

        @Override
        public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
            context.getMetadata().put("brokenStatusSeen", response.getStatus().name());
        }
    }

    private static class CountingCacheableProvider implements FusionDataProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getProviderKey() {
            return "aqi";
        }

        @Override
        public boolean isCacheable() {
            return true;
        }

        @Override
        public FusionProviderResponse fetch(FusionRequest request) {
            calls.incrementAndGet();
            return FusionProviderResponse.success(getProviderKey(), Map.of("currentAqi", 101), 0.8);
        }

        @Override
        public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
            context.setAqi(response.getData());
        }
    }

    private static class ChangingObservedAtProvider implements FusionDataProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getProviderKey() {
            return "aqi";
        }

        @Override
        public FusionProviderResponse fetch(FusionRequest request) {
            int call = calls.incrementAndGet();
            return FusionProviderResponse.success(getProviderKey(), Map.of(
                    "currentAqi", 101 + call,
                    "provider", "CPCB_CAAQMS",
                    "observedAt", "2026-07-12T10:0" + call + ":00Z"
            ), 0.8);
        }

        @Override
        public void contribute(CityEnvironmentalContext context, FusionProviderResponse response) {
            context.setAqi(response.getData());
        }
    }
}


