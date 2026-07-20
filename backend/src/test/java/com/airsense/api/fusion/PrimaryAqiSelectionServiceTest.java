package com.airsense.api.fusion;

import com.airsense.api.attribution.AttributionResult;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.DecisionIntelligenceService;
import com.airsense.api.decision.DecisionRequest;
import com.airsense.api.services.IqAirService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrimaryAqiSelectionServiceTest {

    @Test
    void freshValidCpcbSelectsCpcb() {
        Fixtures fx = fixtures(cpcb(184), iqair(221, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("currentAqi", 184)
                .containsEntry("provider", "CPCB_CAAQMS")
                .containsEntry("standard", "INDIA_NAQI")
                .containsEntry("isFallback", false);
        assertThat(result.get("iqAirEvidence").toString()).contains("Not queried").doesNotContain("4a252");
        verify(fx.iqAir, never()).getNearestCityAqi(anyDouble(), anyDouble());
    }

    @Test
    void cpcbIndianAqiSubIndexUsesBreakpointInterpolation() throws Exception {
        CpcbAqiService cpcb = new CpcbAqiService(new RestTemplateBuilder(), null);
        Method breakpoint = CpcbAqiService.class.getDeclaredMethod("breakpoint", String.class, Double.class);
        breakpoint.setAccessible(true);
        Object bp = breakpoint.invoke(cpcb, "PM25", 78.0);
        Method subIndex = CpcbAqiService.class.getDeclaredMethod("subIndex", double.class, bp.getClass());
        subIndex.setAccessible(true);

        Integer index = (Integer) subIndex.invoke(cpcb, 78.0, bp);

        assertThat(index).isEqualTo(159);
    }

    @Test
    void overallAqiIsMaximumSubIndexFromCpcbCandidate() {
        Map<String, Object> cpcb = cpcb(201);
        Fixtures fx = fixtures(cpcb, iqair(120, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(3), List.of());

        assertThat(result).containsEntry("currentAqi", 201);
        assertThat(result.get("cpcbEvidence").toString()).contains("subIndex=201");
    }

    @Test
    void dominantPollutantComesFromMaximumSubIndex() {
        Map<String, Object> cpcb = cpcb(201);
        Fixtures fx = fixtures(cpcb, iqair(120, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(3), List.of());

        assertThat(result).containsEntry("primaryPollutant", "PM2.5");
    }

    @Test
    void staleCpcbTriggersIqAirFallback() {
        Map<String, Object> cpcb = cpcb(184);
        cpcb.put("freshnessStatus", "STALE");
        Fixtures fx = fixtures(cpcb, iqair(221, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("provider", "IQAIR")
                .containsEntry("standard", "US_AQI")
                .containsEntry("currentAqi", 221)
                .containsEntry("isFallback", true);
        assertThat(result.get("fallbackReason")).isEqualTo("STALE_DATA");
    }

    @Test
    void missingCpcbRecordsTriggerIqAirFallback() {
        Fixtures fx = fixtures(cpcbUnavailable("NO_CPCB_RECORD"), iqair(88, "Lucknow"));

        Map<String, Object> result = fx.service.select(request(), 26.85, 80.95, openWeather(2), List.of());

        assertThat(result).containsEntry("provider", "IQAIR")
                .containsEntry("currentAqi", 88);
    }

    @Test
    void cpcbStationBeyondMaximumDistanceTriggersIqAirFallback() {
        Map<String, Object> cpcb = cpcb(184);
        cpcb.put("distanceKm", 75.0);
        Fixtures fx = fixtures(cpcb, iqair(155, "Noida"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("provider", "IQAIR");
        assertThat(result.get("fallbackReason")).isEqualTo("STATION_TOO_FAR");
    }

    @Test
    void invalidCpcbPollutantValuesTriggerIqAirFallback() {
        Map<String, Object> cpcb = cpcb(184);
        cpcb.put("pollutants", List.of(
                pollutant("PM25", 6001, 184, true),
                pollutant("PM10", 100, 100, true),
                pollutant("NO2", 80, 100, true)
        ));
        Fixtures fx = fixtures(cpcb, iqair(130, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("provider", "IQAIR");
        assertThat(result.get("fallbackReason")).isEqualTo("INVALID_POLLUTANT_VALUES");
    }

    @Test
    void insufficientCpcbPollutantsTriggerIqAirFallback() {
        Map<String, Object> cpcb = cpcb(184);
        cpcb.put("pollutants", List.of(
                pollutant("PM25", 78, 159, true),
                pollutant("NO2", 80, 100, true)
        ));
        Fixtures fx = fixtures(cpcb, iqair(145, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("provider", "IQAIR");
        assertThat(result.get("fallbackReason")).isEqualTo("INSUFFICIENT_POLLUTANTS");
    }

    @Test
    void iqAirFallbackRemainsLabelledUsAqi() {
        Fixtures fx = fixtures(cpcbUnavailable("STALE_DATA"), iqair(221, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("standard", "US_AQI")
                .containsEntry("aqiStandard", "US AQI");
    }

    @Test
    void openWeatherIndexRemainsSeparateFromPrimaryAqi() {
        Fixtures fx = fixtures(cpcb(184), iqair(221, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("currentAqi", 184)
                .containsEntry("openWeatherAqiIndex", 4)
                .containsEntry("openWeatherAqiScale", "OPENWEATHER_1_TO_5");
    }

    @Test
    void differentReturnedIqAirCityIsExposed() {
        Fixtures fx = fixtures(cpcbUnavailable("NO_MATCHING_STATION"), iqair(166, "Ghaziabad"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("providerReturnedCity", "Ghaziabad");
    }

    @Test
    void completeProviderFailureReturnsUnavailableNotZero() {
        Fixtures fx = fixtures(cpcbUnavailable("CPCB_TIMEOUT"), iqairUnavailable());

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result).containsEntry("available", false)
                .containsEntry("provider", "UNAVAILABLE")
                .containsEntry("currentAqi", null);
    }

    @Test
    void citySummaryRequiresConfiguredMinimumFreshSameCityCpcbStations() {
        Map<String, Object> cpcb = cpcb(184);
        cpcb.put("city", "Delhi");
        cpcb.put("stations", List.of(
                Map.of("station", "ITO", "city", "Delhi", "provider", "CPCB_CAAQMS", "standard", "INDIA_NAQI",
                        "freshnessStatus", "LIVE", "observedAt", Instant.now().toString(), "aqi", 184),
                Map.of("station", "Other City", "city", "Mumbai", "provider", "CPCB_CAAQMS", "standard", "INDIA_NAQI",
                        "freshnessStatus", "LIVE", "observedAt", Instant.now().toString(), "aqi", 140),
                Map.of("station", "IQAir", "city", "Delhi", "provider", "IQAIR", "standard", "US_AQI",
                        "freshnessStatus", "LIVE", "observedAt", Instant.now().toString(), "aqi", 90)
        ));
        Fixtures fx = fixtures(cpcb, iqair(120, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(3), List.of());
        Map<String, Object> summary = (Map<String, Object>) result.get("citySummary");

        assertThat(summary).containsEntry("available", false);
        assertThat(summary.get("reason").toString()).contains("Insufficient fresh CPCB city stations");
    }

    @Test
    void citySummaryUsesMedianOfEligibleFreshSameCityCpcbStations() {
        Map<String, Object> cpcb = cpcb(184);
        cpcb.put("city", "Delhi");
        cpcb.put("stations", List.of(
                Map.of("station", "ITO", "city", "Delhi", "provider", "CPCB_CAAQMS", "standard", "INDIA_NAQI",
                        "freshnessStatus", "LIVE", "observedAt", Instant.now().toString(), "aqi", 184),
                Map.of("station", "R K Puram", "city", "Delhi", "provider", "CPCB_CAAQMS", "standard", "INDIA_NAQI",
                        "freshnessStatus", "LIVE", "observedAt", Instant.now().toString(), "aqi", 220),
                Map.of("station", "Old", "city", "Delhi", "provider", "CPCB_CAAQMS", "standard", "INDIA_NAQI",
                        "freshnessStatus", "STALE", "observedAt", Instant.now().minusSeconds(999999).toString(), "aqi", 500)
        ));
        Fixtures fx = fixtures(cpcb, iqair(120, "Delhi"));

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(3), List.of());
        Map<String, Object> summary = (Map<String, Object>) result.get("citySummary");

        assertThat(summary).containsEntry("available", true)
                .containsEntry("freshStationCount", 2)
                .containsEntry("medianAqi", 202);
        assertThat(summary.get("stationNames").toString()).contains("ITO", "R K Puram").doesNotContain("Old");
    }

    @Test
    void apiKeysAreNotIncludedInNormalizedResponse() {
        Map<String, Object> iqair = iqair(123, "Delhi");
        iqair.put("rawIqAir", Map.of("key", "4a25268f-secret"));
        Fixtures fx = fixtures(cpcbUnavailable("NO_CPCB_RECORD"), iqair);

        Map<String, Object> result = fx.service.select(request(), 28.61, 77.23, openWeather(4), List.of());

        assertThat(result.toString()).doesNotContain("4a25268f-secret");
    }

    @Test
    void downstreamDecisionUsesNormalizedSelectedAqi() {
        CityEnvironmentalContext context = CityEnvironmentalContext.builder()
                .city("Delhi")
                .cityId("DELHI")
                .timestamp(Instant.now())
                .aqi(Map.of(
                        "available", true,
                        "selected", Map.of("currentAqi", 184, "standard", "INDIA_NAQI", "provider", "CPCB_CAAQMS"),
                        "currentAqi", 999
                ))
                .providerStatus(Map.of("aqi", "SUCCESS"))
                .providerConfidence(Map.of("aqi", 0.94))
                .build();

        DecisionIntelligenceResult result = new DecisionIntelligenceService(null, null, null, null, null)
                .assemble(context, AttributionResult.builder().overallConfidence(0.5).build(), null, null, null,
                        DecisionRequest.builder().cityId("DELHI").build(), Map.of());

        assertThat(result.getCurrentAQI()).isEqualTo(184);
        assertThat(result.getEnvironmentalSignals()).containsEntry("standard", "INDIA_NAQI");
    }

    private Fixtures fixtures(Map<String, Object> cpcbResponse, Map<String, Object> iqAirResponse) {
        CpcbAqiService cpcb = mock(CpcbAqiService.class);
        IqAirService iqAir = mock(IqAirService.class);
        CurrentAqiProperties properties = new CurrentAqiProperties();
        properties.getCpcb().setMaxStationDistanceKm(50);
        when(cpcb.fetchCanonicalAqi(any(FusionRequest.class), anyDouble(), anyDouble())).thenReturn(cpcbResponse);
        when(iqAir.getNearestCityAqi(anyDouble(), anyDouble())).thenReturn(iqAirResponse);
        return new Fixtures(new PrimaryAqiSelectionService(cpcb, iqAir, properties), iqAir);
    }

    private FusionRequest request() {
        return FusionRequest.builder()
                .cityId("Delhi")
                .cityName("Delhi")
                .state("Delhi")
                .country("India")
                .latitude(28.61)
                .longitude(77.23)
                .build();
    }

    private Map<String, Object> cpcb(int aqi) {
        Map<String, Object> cpcb = new java.util.LinkedHashMap<>();
        cpcb.put("available", true);
        cpcb.put("aqi", aqi);
        cpcb.put("aqiCategory", "MODERATE");
        cpcb.put("provider", "CPCB_CAAQMS");
        cpcb.put("stationName", "ITO");
        cpcb.put("stationLatitude", 28.62);
        cpcb.put("stationLongitude", 77.24);
        cpcb.put("distanceKm", 4.8);
        cpcb.put("observedAt", Instant.now().minusSeconds(600).toString());
        cpcb.put("freshnessStatus", "LIVE");
        cpcb.put("prominentPollutant", "PM2.5");
        cpcb.put("pollutants", List.of(
                pollutant("PM25", 78, 159, true),
                pollutant("PM10", 101, 101, true),
                pollutant("NO2", 181, 201, true)
        ));
        cpcb.put("calculation", Map.of("method", "max_valid_pollutant_sub_index"));
        cpcb.put("stations", List.of(Map.of("lookupCity", "Delhi", "lookupState", "Delhi")));
        return cpcb;
    }

    private Map<String, Object> cpcbUnavailable(String reason) {
        Map<String, Object> cpcb = new java.util.LinkedHashMap<>();
        cpcb.put("available", false);
        cpcb.put("provider", "CPCB_CAAQMS");
        cpcb.put("reason", reason);
        return cpcb;
    }

    private Map<String, Object> iqair(int aqi, String city) {
        Map<String, Object> iqair = new java.util.LinkedHashMap<>();
        iqair.put("available", true);
        iqair.put("currentAqi", aqi);
        iqair.put("aqiCategory", "Unhealthy");
        iqair.put("provider", "IQAir AirVisual");
        iqair.put("city", city);
        iqair.put("stationName", city + " - IQAir AirVisual");
        iqair.put("observedAt", Instant.now().minusSeconds(300).toString());
        iqair.put("freshnessStatus", "LIVE");
        iqair.put("coordinates", Map.of("latitude", 28.61, "longitude", 77.23));
        iqair.put("providerCoordinates", Map.of("latitude", 28.62, "longitude", 77.24));
        return iqair;
    }

    private Map<String, Object> iqairUnavailable() {
        Map<String, Object> iqair = new java.util.LinkedHashMap<>();
        iqair.put("available", false);
        iqair.put("currentAqi", null);
        iqair.put("reason", "IQAIR_TIMEOUT");
        return iqair;
    }

    private Map<String, Object> openWeather(int index) {
        return Map.of(
                "available", true,
                "openWeatherAqiIndex", index,
                "openWeatherAqiCategory", "Poor",
                "pm25", 70.0,
                "pm10", 110.0,
                "no2", 42.0,
                "timestamp", Instant.now().toString()
        );
    }

    private Map<String, Object> pollutant(String pollutant, double concentration, int subIndex, boolean valid) {
        return Map.of(
                "pollutant", pollutant,
                "normalizedPollutant", pollutant,
                "concentration", concentration,
                "valid", valid,
                "subIndex", subIndex
        );
    }

    private record Fixtures(PrimaryAqiSelectionService service, IqAirService iqAir) {
    }
}


