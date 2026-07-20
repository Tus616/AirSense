package com.airsense.api.config;

import com.airsense.api.entities.User;
import com.airsense.api.entities.CityMetricsSnapshot;
import com.airsense.api.repositories.UserRepository;
import com.airsense.api.repositories.CityMetricsSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import com.airsense.api.services.ConstructionPermitSeedService;
import com.airsense.api.services.IndustrialSourceSeedService;
import com.airsense.api.services.ThermalAnomalyService;
import com.airsense.api.services.CitySeedService;
import com.airsense.api.services.VulnerabilitySeedService;
import com.airsense.api.services.SyntheticDataSeedService;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.Map;

/**
 * DatabaseSeeder — seeds initial data on startup.
 *
 * <p>Demo user seeding is DISABLED by default. To enable it in a local development
 * environment, set the following environment variables:
 *
 * <pre>
 *   DEMO_SEED_ENABLED=true
 *   DEMO_ADMIN_EMAIL=your-local-admin@example.com
 *   DEMO_ADMIN_PASSWORD=your-local-admin-password
 *   DEMO_CITIZEN_EMAIL=your-local-citizen@example.com
 *   DEMO_CITIZEN_PASSWORD=your-local-citizen-password
 * </pre>
 *
 * <p>Do NOT provide credential defaults here, in application.yml, or in .env.example.
 * If seeding is enabled but credentials are absent, seeding is skipped with a warning.
 * Passwords are always BCrypt-encoded before storage — plaintext is never persisted.
 */
@Slf4j
@Component
public class DatabaseSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CityMetricsSnapshotRepository snapshotRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ConstructionPermitSeedService constructionPermitSeedService;

    @Autowired
    private IndustrialSourceSeedService industrialSourceSeedService;

    @Autowired
    private ThermalAnomalyService thermalAnomalyService;

    @Autowired
    private CitySeedService citySeedService;

    @Autowired
    private VulnerabilitySeedService vulnerabilitySeedService;

    @Autowired
    private SyntheticDataSeedService syntheticDataSeedService;

    @Autowired
    private AirQualityOperationsProperties airQualityOperationsProperties;

    // Demo seeding is controlled entirely through environment variables.
    // No credential defaults are provided here.
    @Value("${DEMO_SEED_ENABLED:false}")
    private boolean demoSeedEnabled;

    @Value("${DEMO_ADMIN_EMAIL:}")
    private String demoAdminEmail;

    @Value("${DEMO_ADMIN_PASSWORD:}")
    private String demoAdminPassword;

    @Value("${DEMO_CITIZEN_EMAIL:}")
    private String demoCitizenEmail;

    @Value("${DEMO_CITIZEN_PASSWORD:}")
    private String demoCitizenPassword;

    @Override
    public void run(String... args) {
        seedUsers();
        if (!airQualityOperationsProperties.isDemoDataEnabled()) {
            log.info("Demo data seeding disabled; production runtime will not create synthetic AQI, metrics, source, or forecast fixtures.");
            return;
        }
        seedCityMetrics();
        citySeedService.seedAndBackfill();
        vulnerabilitySeedService.seedData();
        constructionPermitSeedService.seedData();
        industrialSourceSeedService.seedData();
        thermalAnomalyService.seedData();
        syntheticDataSeedService.seedData();
    }

    private void seedUsers() {
        if (!demoSeedEnabled) {
            log.info("Demo user seeding disabled (DEMO_SEED_ENABLED=false). Skipping demo user creation.");
            return;
        }

        // Validate that required credentials are present before attempting to seed.
        // Never log or print credential values.
        boolean adminCredsPresent = !demoAdminEmail.isBlank() && !demoAdminPassword.isBlank();
        boolean citizenCredsPresent = !demoCitizenEmail.isBlank() && !demoCitizenPassword.isBlank();

        if (!adminCredsPresent && !citizenCredsPresent) {
            log.warn("Demo seeding is enabled (DEMO_SEED_ENABLED=true) but no demo credentials were provided. "
                    + "Set DEMO_ADMIN_EMAIL, DEMO_ADMIN_PASSWORD, DEMO_CITIZEN_EMAIL, and DEMO_CITIZEN_PASSWORD "
                    + "to seed demo users. Skipping.");
            return;
        }

        if (adminCredsPresent) {
            if (userRepository.findByEmail(demoAdminEmail).isEmpty()) {
                userRepository.save(User.builder()
                        .email(demoAdminEmail)
                        .password(passwordEncoder.encode(demoAdminPassword))
                        .name("Admin Official")
                        .role("ADMIN")
                        .build());
                log.info("Demo admin user created (email address not logged for privacy).");
            } else {
                log.debug("Demo admin user already exists; skipping.");
            }
        } else {
            log.warn("Demo admin credentials incomplete (DEMO_ADMIN_EMAIL or DEMO_ADMIN_PASSWORD missing). Skipping admin seed.");
        }

        if (citizenCredsPresent) {
            if (userRepository.findByEmail(demoCitizenEmail).isEmpty()) {
                userRepository.save(User.builder()
                        .email(demoCitizenEmail)
                        .password(passwordEncoder.encode(demoCitizenPassword))
                        .name("Citizen User")
                        .role("CITIZEN")
                        .build());
                log.info("Demo citizen user created (email address not logged for privacy).");
            } else {
                log.debug("Demo citizen user already exists; skipping.");
            }
        } else {
            log.warn("Demo citizen credentials incomplete (DEMO_CITIZEN_EMAIL or DEMO_CITIZEN_PASSWORD missing). Skipping citizen seed.");
        }
    }

    private void seedCityMetrics() {
        if (snapshotRepository.count() == 0) {
            String[] cities = {"DELHI", "MUMBAI", "BENGALURU"};
            int[] aqis = {210, 150, 85};
            String[] sources = {"Traffic & Dust", "Industrial & Sea Salt", "Traffic"};

            for (int i = 0; i < cities.length; i++) {
                CityMetricsSnapshot snap = new CityMetricsSnapshot();
                snap.setCityId(cities[i]);
                snap.setTimestamp(Instant.now());
                snap.setCurrentAqi(aqis[i]);
                snap.setForecastPeakAqi(aqis[i] + 45);
                snap.setDominantSource(sources[i]);
                snap.setSourceMix(Map.of("Traffic", 0.4, "Industrial", 0.3, "Dust", 0.3));
                snap.setForecastRmse(12.5 + i * 2);
                snap.setBaselineRmse(18.0 + i * 3);
                snap.setOpenEnforcementCount(15 - i * 4);
                snap.setResolvedEnforcementCount(50 - i * 10);
                snap.setAvgResponseTimeHours(24.5 - i * 5);
                snap.setAdvisoryCount(3 - i);

                snapshotRepository.save(snap);
            }
        }
    }
}
