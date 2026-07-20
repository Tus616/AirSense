package com.airsense.api.repositories;

import com.airsense.api.entities.TrackedAirQualityLocation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackedAirQualityLocationRepository extends MongoRepository<TrackedAirQualityLocation, String> {
    Optional<TrackedAirQualityLocation> findByLocationKey(String locationKey);

    Optional<TrackedAirQualityLocation> findFirstByLocationKeyIn(List<String> locationKeys);

    long countByLocationKeyIn(List<String> locationKeys);

    List<TrackedAirQualityLocation> findByTrackingEnabledTrueOrderByLastSearchedAtDesc();
}
