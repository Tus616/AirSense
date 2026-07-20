package com.airsense.api.forecast;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import com.airsense.api.repositories.AqiHistoricalSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.calls;

class HistoricalForecastReplayServiceTest {
    private static final String ARCHIVE_ORIGIN = "HISTORICAL_TRAINING_ARCHIVE";

    @Test
    void replayUsesOnlyIssueTimeObservationAndLoadsFutureActualsAfterPrediction() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2025-01-01T10:00:00Z");
        AqiHistoricalSnapshot issue = snapshot("delhi_ito", "INDIA_NAQI", issueTime, 150);
        AqiHistoricalSnapshot actual24 = snapshot("delhi_ito", "INDIA_NAQI", issueTime.plus(Duration.ofHours(24)), 180);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(issue));
        when(repository.findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class)))
                .thenReturn(List.of(actual24), List.of(), List.of());

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("delhi_ito", issueTime));

        assertThat(response.getStatus()).isEqualTo("AVAILABLE");
        assertThat(response.getIssueTimeAqi()).isEqualTo(150);
        assertThat(response.getResults()).hasSize(3);
        HistoricalForecastReplayService.HistoricalReplayHorizonResponse first = response.getResults().get(0);
        assertThat(first.getPredictedAqi()).isEqualTo(150);
        assertThat(first.getActualAqi()).isEqualTo(180);
        assertThat(first.getAbsoluteError()).isEqualTo(30.0);
        assertThat(first.getPercentageError()).isCloseTo(16.666, org.assertj.core.data.Offset.offset(0.01));

        InOrder order = inOrder(repository);
        order.verify(repository).findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN));
        order.verify(repository, calls(1)).findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class));
    }

    @Test
    void replayTimelineFramesAreHistoricalOnlyAndCurrentFrameMatchesIssueObservation() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2023-01-18T17:14:00Z");
        AqiHistoricalSnapshot prior = snapshot("delhi_ito", "INDIA_NAQI", issueTime.minus(Duration.ofHours(1)), 142);
        AqiHistoricalSnapshot issue = snapshot("delhi_ito", "INDIA_NAQI", issueTime, 155);
        AqiHistoricalSnapshot actual24 = snapshot("delhi_ito", "INDIA_NAQI", issueTime.plus(Duration.ofHours(24)), 180);
        AqiHistoricalSnapshot actual48 = snapshot("delhi_ito", "INDIA_NAQI", issueTime.plus(Duration.ofHours(48)), 165);
        AqiHistoricalSnapshot actual72 = snapshot("delhi_ito", "INDIA_NAQI", issueTime.plus(Duration.ofHours(72)), 170);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(prior, issue));
        when(repository.findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class)))
                .thenReturn(List.of(actual24), List.of(actual48), List.of(actual72));

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("delhi_ito", issueTime));

        assertThat(response.getTimelineFrames()).hasSize(5);
        assertThat(response.getTimelineFrames()).extracting(HistoricalForecastReplayService.HistoricalReplayTimelineFrameResponse::getLabel)
                .containsExactly("Historical", "Current", "+24h", "+48h", "+72h");
        HistoricalForecastReplayService.HistoricalReplayTimelineFrameResponse current = response.getTimelineFrames().get(1);
        assertThat(current.getTimestamp()).isEqualTo(response.getIssueObservationTime());
        assertThat(current.getTimestamp()).isEqualTo(issue.getProviderObservedAt());
        assertThat(current.getAqi()).isEqualTo(155);
        assertThat(current.getRisk()).containsKey("overallRiskLevel");
        assertThat(current.getPollutants()).containsEntry("pm25", 10.0);
        assertThat(current.getWeather()).containsKey("temperatureCelsius");
        assertThat(response.getTimelineFrames()).allSatisfy(frame -> {
            assertThat(frame.getStationKey()).isEqualTo(response.getStationKey());
            assertThat(frame.getReplayRunId()).isEqualTo(response.getReplayRunId());
            if (!Boolean.TRUE.equals(frame.getUnavailable())) {
                assertThat(frame.getTimestamp()).isBefore(Instant.parse("2026-01-01T00:00:00Z"));
            }
        });
        assertThat(response.getTimelineFrames().get(0).getTimestamp()).isEqualTo(prior.getProviderObservedAt());
        assertThat(response.getTimelineFrames().get(2).getTimestamp()).isEqualTo(actual24.getProviderObservedAt());
        assertThat(response.getTimelineFrames().get(3).getTimestamp()).isEqualTo(actual48.getProviderObservedAt());
        assertThat(response.getTimelineFrames().get(4).getTimestamp()).isEqualTo(actual72.getProviderObservedAt());
    }

    @Test
    void missingReplayFrameProducesExplicitUnavailableState() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2023-01-18T17:14:00Z");
        AqiHistoricalSnapshot issue = snapshot("delhi_ito", "INDIA_NAQI", issueTime, 155);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(issue));
        when(repository.findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class)))
                .thenReturn(List.of(), List.of(), List.of());

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("delhi_ito", issueTime));

        assertThat(response.getTimelineFrames()).hasSize(5);
        assertThat(response.getTimelineFrames().get(0).getUnavailable()).isTrue();
        assertThat(response.getTimelineFrames().get(0).getMessage()).isEqualTo("Historical frame unavailable");
        assertThat(response.getTimelineFrames().get(1).getUnavailable()).isFalse();
        assertThat(response.getTimelineFrames().subList(2, 5)).allSatisfy(frame -> {
            assertThat(frame.getUnavailable()).isTrue();
            assertThat(frame.getMessage()).isEqualTo("Historical frame unavailable");
            assertThat(frame.getTimestamp()).isNull();
        });
    }

    @Test
    void noRecordAfterIssueTimeIsUsedForFeatureGeneration() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2025-01-01T10:00:00Z");
        AqiHistoricalSnapshot future = snapshot("delhi_ito", "INDIA_NAQI", issueTime.plusSeconds(1), 999);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(future));

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("delhi_ito", issueTime));

        assertThat(response.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getResults()).allSatisfy(row -> assertThat(row.getPredictedAqi()).isNull());
        verify(repository, never()).findReplayActualSnapshots(anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void actualFutureAqiCannotAffectPredictedAqi() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2025-01-01T10:00:00Z");
        AqiHistoricalSnapshot issue = snapshot("lucknow_gomti_nagar", "INDIA_NAQI", issueTime, 90);
        AqiHistoricalSnapshot extremeActual = snapshot("lucknow_gomti_nagar", "INDIA_NAQI", issueTime.plus(Duration.ofHours(24)), 450);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(issue));
        when(repository.findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class)))
                .thenReturn(List.of(extremeActual), List.of(), List.of());

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("lucknow_gomti_nagar", issueTime));

        assertThat(response.getResults().get(0).getPredictedAqi()).isEqualTo(90);
        assertThat(response.getResults().get(0).getActualAqi()).isEqualTo(450);
    }

    @Test
    void sameStationAndSameStandardAreEnforcedAfterRepositoryLookup() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2025-01-01T10:00:00Z");
        AqiHistoricalSnapshot wrongStation = snapshot("some_other_station", "INDIA_NAQI", issueTime, 111);
        AqiHistoricalSnapshot wrongStandard = snapshot("delhi_ito", "US_AQI", issueTime, 222);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(wrongStation, wrongStandard));

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("delhi_ito", issueTime));

        assertThat(response.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getMessage()).contains("same-station INDIA_NAQI");
    }

    @Test
    void missingFutureActualRemainsNullNotZero() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2025-01-01T10:00:00Z");
        AqiHistoricalSnapshot issue = snapshot("lucknow_gomti_nagar", "INDIA_NAQI", issueTime, 90);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(issue));
        when(repository.findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class)))
                .thenReturn(List.of());

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("lucknow_gomti_nagar", issueTime));

        assertThat(response.getResults()).allSatisfy(horizon -> {
            assertThat(horizon.getPredictedAqi()).isEqualTo(90);
            assertThat(horizon.getActualAqi()).isNull();
            assertThat(horizon.getAbsoluteError()).isNull();
            assertThat(horizon.getPercentageError()).isNull();
        });
    }

    @Test
    void percentageErrorHandlesActualAqiZeroSafely() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2025-01-01T10:00:00Z");
        AqiHistoricalSnapshot issue = snapshot("delhi_ito", "INDIA_NAQI", issueTime, 20);
        AqiHistoricalSnapshot zeroActual = snapshot("delhi_ito", "INDIA_NAQI", issueTime.plus(Duration.ofHours(24)), 0);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(issue));
        when(repository.findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class)))
                .thenReturn(List.of(zeroActual), List.of(), List.of());

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("delhi_ito", issueTime));

        assertThat(response.getResults().get(0).getActualAqi()).isEqualTo(0);
        assertThat(response.getResults().get(0).getAbsoluteError()).isEqualTo(20.0);
        assertThat(response.getResults().get(0).getPercentageError()).isNull();
    }

    @Test
    void timestampToleranceIsAppliedToActualLookups() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant issueTime = Instant.parse("2025-01-01T10:00:00Z");
        AqiHistoricalSnapshot issue = snapshot("delhi_ito", "INDIA_NAQI", issueTime, 150);
        AqiHistoricalSnapshot insideTolerance = snapshot("delhi_ito", "INDIA_NAQI",
                issueTime.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(90)), 151);

        when(repository.findReplayIssueSnapshots(anyList(), eq("INDIA_NAQI"), eq(issueTime), eq(ARCHIVE_ORIGIN)))
                .thenReturn(List.of(issue));
        when(repository.findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"), any(Instant.class), any(Instant.class), eq(ARCHIVE_ORIGIN), any(Pageable.class)))
                .thenReturn(List.of(insideTolerance), List.of(), List.of());

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("delhi_ito", issueTime));

        assertThat(response.getResults().get(0).getActualAqi()).isEqualTo(151);
        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository, times(3)).findReplayActualSnapshots(anyList(), eq("INDIA_NAQI"),
                startCaptor.capture(), endCaptor.capture(), eq(ARCHIVE_ORIGIN), any(Pageable.class));
        assertThat(startCaptor.getAllValues().get(0)).isEqualTo(issueTime.plus(Duration.ofHours(24)).minus(Duration.ofMinutes(90)));
        assertThat(endCaptor.getAllValues().get(0)).isEqualTo(issueTime.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(90)));
    }

    @Test
    void stationCatalogueReportsRequestedContractFields() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);
        Instant first = Instant.parse("2025-01-01T00:00:00Z");
        Instant last = Instant.parse("2025-01-05T00:00:00Z");
        AqiHistoricalSnapshot firstSnapshot = snapshot("bandra_kurla_complex_mumbai_mpcb", "INDIA_NAQI", first, 80);
        AqiHistoricalSnapshot replayableSnapshot = snapshot("bandra_kurla_complex_mumbai_mpcb", "INDIA_NAQI", first.plus(Duration.ofHours(72)), 88);
        AqiHistoricalSnapshot lastSnapshot = snapshot("bandra_kurla_complex_mumbai_mpcb", "INDIA_NAQI", last, 95);

        when(repository.findReplaySnapshotsByIdentityKeysAndStandard(anyList(), eq("INDIA_NAQI"), eq(ARCHIVE_ORIGIN), any(Sort.class)))
                .thenReturn(List.of(), List.of(), List.of(firstSnapshot, replayableSnapshot, lastSnapshot));

        List<HistoricalForecastReplayService.HistoricalReplayStationResponse> stations = service.stations();

        HistoricalForecastReplayService.HistoricalReplayStationResponse bkc = stations.stream()
                .filter(station -> station.getStationKey().equals("bandra_kurla_complex_mumbai_mpcb"))
                .findFirst()
                .orElseThrow();
        assertThat(bkc.getStationName()).isEqualTo("bandra_kurla_complex_mumbai_mpcb");
        assertThat(bkc.getProvider()).isEqualTo("CPCB_CAAQMS");
        assertThat(bkc.getAqiStandard()).isEqualTo("INDIA_NAQI");
        assertThat(bkc.getEarliestReplayTimestamp()).isEqualTo(first);
        assertThat(bkc.getLatestReplayTimestamp()).isEqualTo(last.minus(Duration.ofHours(72)));
        assertThat(bkc.getTimezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void unsupportedStationDoesNotQueryArchive() {
        AqiHistoricalSnapshotRepository repository = mock(AqiHistoricalSnapshotRepository.class);
        HistoricalForecastReplayService service = service(repository);

        HistoricalForecastReplayService.HistoricalReplayResponse response = service.replay(
                new HistoricalForecastReplayService.HistoricalReplayRequest("unsupported_station", Instant.parse("2025-01-01T00:00:00Z")));

        assertThat(response.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getResults()).allSatisfy(row -> assertThat(row.getFallbackReason()).isEqualTo("UNSUPPORTED_REPLAY_STATION"));
        verify(repository, never()).findReplayIssueSnapshots(anyList(), any(), any(), any());
    }

    private HistoricalForecastReplayService service(AqiHistoricalSnapshotRepository repository) {
        return new HistoricalForecastReplayService(repository, mock(MongoTemplate.class), mock(Environment.class));
    }

    private AqiHistoricalSnapshot snapshot(String stationKey, String standard, Instant observedAt, int aqi) {
        return AqiHistoricalSnapshot.builder()
                .id("snap-" + stationKey + "-" + observedAt)
                .locationKey(stationKey)
                .stationLocationKey(stationKey)
                .stationKey(stationKey)
                .stationName(stationKey)
                .provider("CPCB_CAAQMS")
                .aqiStandard(standard)
                .currentAqi(aqi)
                .providerObservedAt(observedAt)
                .dataOrigin(ARCHIVE_ORIGIN)
                .pm25(10.0)
                .pm10(20.0)
                .temperatureCelsius(22.0)
                .humidityPercent(55.0)
                .windSpeedMps(2.5)
                .build();
    }
}
