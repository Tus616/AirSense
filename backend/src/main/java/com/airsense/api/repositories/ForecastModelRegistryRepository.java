package com.airsense.api.repositories;

import com.airsense.api.entities.ForecastModelRegistryEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ForecastModelRegistryRepository extends MongoRepository<ForecastModelRegistryEntry, String> {
    Optional<ForecastModelRegistryEntry> findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(
            String aqiStandard,
            Integer horizonHours,
            String modelScope
    );

    Optional<ForecastModelRegistryEntry> findByAqiStandardAndHorizonHoursAndModelScopeAndVersion(
            String aqiStandard,
            Integer horizonHours,
            String modelScope,
            String version
    );
}
