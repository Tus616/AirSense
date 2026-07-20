package com.airsense.api.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoAirQualityIndexInitializerTest {

    @Test
    void exposesRequiredOperationalIndexesWithoutRelyingOnAnnotations() {
        MongoAirQualityIndexInitializer initializer = new MongoAirQualityIndexInitializer(null, new AirQualityOperationsProperties());

        List<MongoAirQualityIndexInitializer.IndexDefinitionSpec> indexes = initializer.requiredIndexes();

        assertThat(indexes).extracting(MongoAirQualityIndexInitializer.IndexDefinitionSpec::name)
                .contains(
                        "uq_aqi_snapshot_identity",
                        "idx_aqi_history_lookup",
                        "idx_aqi_history_ingested",
                        "uq_tracked_location_key",
                        "idx_tracked_location_active",
                        "idx_forecast_evaluation_lookup",
                        "idx_forecast_metrics_lookup",
                        "idx_forecast_identity"
                );
        assertThat(indexes.stream().filter(index -> "uq_aqi_snapshot_identity".equals(index.name())).findFirst())
                .get()
                .satisfies(index -> {
                    assertThat(index.unique()).isTrue();
                    assertThat(index.keys().keySet()).containsExactly("locationKey", "provider", "aqiStandard", "providerObservedAt");
                });
    }
}


