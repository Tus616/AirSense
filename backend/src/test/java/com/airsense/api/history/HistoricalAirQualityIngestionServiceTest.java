package com.airsense.api.history;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.config.AirQualityOperationsProperties;
import com.airsense.api.fusion.CityEnvironmentalContext;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import com.airsense.api.repositories.TrackedAirQualityLocationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalAirQualityIngestionServiceTest {

    @Test
    void duplicateSnapshotInsertReturnsExistingObservationWithoutFailure() {
        AqiHistoricalSnapshotRepository snapshotRepository = mock(AqiHistoricalSnapshotRepository.class);
        TrackedAirQualityLocationRepository trackedRepository = mock(TrackedAirQualityLocationRepository.class);
        HistoricalAirQualityIngestionService service = new HistoricalAirQualityIngestionService(
                null, snapshotRepository, trackedRepository, new HistoricalAqiProperties(), identityService());
        Instant observedAt = Instant.now();
        AqiHistoricalSnapshot existing = AqiHistoricalSnapshot.builder()
                .id("existing")
                .locationKey("in:28.600:77.200")
                .provider("CPCB_CAAQMS")
                .aqiStandard("INDIA_NAQI")
                .providerObservedAt(observedAt)
                .currentAqi(184)
                .build();

        when(snapshotRepository.findFirstByLocationKeyInAndProviderAndAqiStandardAndProviderObservedAt(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq("CPCB_CAAQMS"),
                org.mockito.ArgumentMatchers.eq("INDIA_NAQI"), org.mockito.ArgumentMatchers.eq(observedAt)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(snapshotRepository.save(any(AqiHistoricalSnapshot.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(trackedRepository.findByLocationKey("in:28.600:77.200")).thenReturn(Optional.empty());

        HistoricalAirQualityIngestionService.IngestionResult result = service.saveFromContext(
                new HistoricalAirQualityIngestionService.LocationRequest("Delhi", "Delhi", "Delhi", "India", 28.6, 77.2),
                CityEnvironmentalContext.builder()
                        .aqi(Map.of(
                                "selected", Map.of(
                                        "currentAqi", 184,
                                        "standard", "INDIA_NAQI",
                                        "provider", "CPCB_CAAQMS",
                                        "observedAt", observedAt.toString()
                                )
                        ))
                        .weather(Map.of("temperature", 30, "humidity", 50, "pressure", 1000))
                        .build());

        assertThat(result.inserted()).isFalse();
        assertThat(result.status()).isEqualTo("ALREADY_EXISTS");
        assertThat(result.observation().getId()).isEqualTo("existing");
    }

    private CanonicalLocationIdentityService identityService() {
        return new CanonicalLocationIdentityService(new AirQualityOperationsProperties());
    }
}


