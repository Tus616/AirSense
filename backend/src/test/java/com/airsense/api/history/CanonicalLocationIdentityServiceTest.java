package com.airsense.api.history;

import com.airsense.api.config.AirQualityOperationsProperties;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalLocationIdentityServiceTest {

    private final CanonicalLocationIdentityService service =
            new CanonicalLocationIdentityService(new AirQualityOperationsProperties());

    @Test
    void sameCoordinatesUseStableCountryAndRoundedCoordinateKey() {
        CanonicalLocationIdentity delhi = service.identity("Delhi", "Delhi", "India", 28.6139, 77.2090);
        CanonicalLocationIdentity newDelhi = service.identity("New Delhi", "NCT of Delhi", "IN", 28.6139, 77.2090);

        assertThat(delhi.locationKey()).isEqualTo("in:28.614:77.209");
        assertThat(newDelhi.locationKey()).isEqualTo(delhi.locationKey());
        assertThat(delhi.keyVersion()).isEqualTo("v2");
    }

    @Test
    void coordinateFormattingIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertThat(service.identity("Delhi", "Delhi", "India", 28.6139, 77.2090).locationKey())
                    .isEqualTo("in:28.614:77.209");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void negativeCoordinatesKeepLatitudeLongitudeOrder() {
        CanonicalLocationIdentity identity = service.identity("Quito", "", "EC", -0.180653, -78.467834);

        assertThat(identity.locationKey()).isEqualTo("ec:-0.181:-78.468");
        assertThat(identity.roundedLatitude()).isEqualTo(-0.181);
        assertThat(identity.roundedLongitude()).isEqualTo(-78.468);
    }

    @Test
    void legacyCandidatesIncludeOldCityBasedFormat() {
        CanonicalLocationIdentity identity = service.identity("Delhi", "Delhi", "India", 28.6139, 77.2090);

        assertThat(identity.legacyLocationKeys()).contains("india:delhi:28.614:77.209");
    }
}


