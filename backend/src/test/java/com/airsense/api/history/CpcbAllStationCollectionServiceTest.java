package com.airsense.api.history;

import com.airsense.api.config.AirQualityOperationsProperties;
import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.fusion.CpcbAqiService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CpcbAllStationCollectionServiceTest {

    @Test
    void scheduledCollectorStoresEveryValidProviderStation() {
        CpcbAqiService cpcb = mock(CpcbAqiService.class);
        HistoricalAirQualityIngestionService ingestion = mock(HistoricalAirQualityIngestionService.class);
        HistoricalAqiProperties properties = new HistoricalAqiProperties();
        properties.setStaleAfterMinutes(10_000);
        CpcbAllStationCollectionService service = new CpcbAllStationCollectionService(
                cpcb, ingestion, new CanonicalLocationIdentityService(new AirQualityOperationsProperties()), properties);
        Instant observedAt = Instant.now();
        when(cpcb.fetchAllStationObservations()).thenReturn(new CpcbAqiService.AllStationFetchResult(
                8, 8, 2,
                List.of(station("Alpha Station", observedAt), station("Beta Station", observedAt)),
                observedAt, 1, List.of()));
        when(ingestion.saveProviderSnapshot(any(AqiHistoricalSnapshot.class)))
                .thenAnswer(invocation -> new HistoricalAirQualityIngestionService.IngestionResult(
                        invocation.getArgument(0), true, List.of(), "INSERTED", "NOT_ATTEMPTED"));

        CpcbAllStationCollectionService.CollectionRunResult result = service.collectAllStations();

        assertThat(result.totalStationsReturnedByProvider()).isEqualTo(8);
        assertThat(result.totalStationsDiscovered()).isEqualTo(2);
        assertThat(result.validObservations()).isEqualTo(2);
        assertThat(result.insertedObservations()).isEqualTo(2);
        verify(ingestion, times(2)).saveProviderSnapshot(any(AqiHistoricalSnapshot.class));
    }

    private Map<String, Object> station(String name, Instant observedAt) {
        return Map.of(
                "station", name,
                "city", "Delhi",
                "state", "Delhi",
                "stationLatitude", 28.61,
                "stationLongitude", 77.23,
                "aqi", 120,
                "prominentPollutant", "PM2.5",
                "observedAt", observedAt.toString(),
                "pollutants", List.of(
                        Map.of("normalizedPollutant", "PM25", "concentration", 80),
                        Map.of("normalizedPollutant", "PM10", "concentration", 140),
                        Map.of("normalizedPollutant", "NO2", "concentration", 40)
                )
        );
    }
}
