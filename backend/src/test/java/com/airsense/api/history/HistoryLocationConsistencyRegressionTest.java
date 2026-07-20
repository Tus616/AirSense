package com.airsense.api.history;

import com.airsense.api.config.AirQualityOperationsProperties;
import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.forecast.ForecastEngineProperties;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import com.airsense.api.repositories.TrackedAirQualityLocationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoryLocationConsistencyRegressionTest {

    @Test
    void alreadyExistsSnapshotIsVisibleToQualityForSameDelhiRequest() {
        CanonicalLocationIdentityService identityService = new CanonicalLocationIdentityService(new AirQualityOperationsProperties());
        AqiHistoricalSnapshotRepository snapshotRepository = mock(AqiHistoricalSnapshotRepository.class);
        TrackedAirQualityLocationRepository trackedRepository = mock(TrackedAirQualityLocationRepository.class);
        ForecastEngineProperties forecastProperties = new ForecastEngineProperties();
        HistoricalAirQualityIngestionService ingestionService = new HistoricalAirQualityIngestionService(
                null, snapshotRepository, trackedRepository, new HistoricalAqiProperties(), identityService);
        AirQualityHistoryService historyService = new AirQualityHistoryService(
                snapshotRepository, forecastProperties, identityService, new HistoricalAqiProperties());

        Instant observedAt = Instant.now().plusSeconds(60);
        AqiHistoricalSnapshot existing = AqiHistoricalSnapshot.builder()
                .id("snapshot-delhi")
                .locationKey("in:28.614:77.209")
                .locationKeyVersion("v2")
                .canonicalLocationKey("in:28.614:77.209")
                .provider("CPCB_CAAQMS")
                .aqiStandard("INDIA_NAQI")
                .providerObservedAt(observedAt)
                .currentAqi(151)
                .dataOrigin(DataOrigin.DERIVED_FROM_REAL_DATA.name())
                .dataQualityStatus("PARTIAL")
                .build();

        when(snapshotRepository.findFirstByLocationKeyInAndProviderAndAqiStandardAndProviderObservedAt(
                anyList(), eq("CPCB_CAAQMS"), eq("INDIA_NAQI"), eq(observedAt)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(snapshotRepository.save(any(AqiHistoricalSnapshot.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(trackedRepository.findByLocationKey("in:28.614:77.209")).thenReturn(Optional.empty());
        when(snapshotRepository.findByLocationKeyInAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
                anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(existing));

        HistoricalAirQualityIngestionService.IngestionResult refresh = ingestionService.saveFromContext(
                new HistoricalAirQualityIngestionService.LocationRequest("Delhi", "Delhi", "Delhi", "India", 28.6139, 77.2090),
                CityEnvironmentalContext.builder()
                        .aqi(Map.of("selected", Map.of(
                                "currentAqi", 151,
                                "standard", "INDIA_NAQI",
                                "provider", "CPCB_CAAQMS",
                                "observedAt", observedAt.toString()
                        )))
                        .weather(Map.of("temperature", 30, "humidity", 50, "pressure", 1000))
                        .build());
        Map<String, Object> quality = historyService.quality("Delhi", "Delhi", "India", 28.6139, 77.2090, 30);

        assertThat(refresh.status()).isEqualTo("ALREADY_EXISTS");
        assertThat(refresh.observation().getLocationKey()).isEqualTo("in:28.614:77.209");
        assertThat(quality.get("totalObservations")).isEqualTo(1);
        assertThat(quality.get("uniqueObservationCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) quality.get("location");
        assertThat(location.get("locationKey")).isEqualTo(refresh.observation().getLocationKey());
        org.mockito.ArgumentCaptor<Instant> queryEnd = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(snapshotRepository).findByLocationKeyInAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
                anyList(), any(Instant.class), queryEnd.capture());
        assertThat(queryEnd.getValue()).isAfter(observedAt);
    }
}


