package com.airsense.api.repositories;

import com.airsense.api.entities.CityMetricsSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CityMetricsSnapshotRepository extends MongoRepository<CityMetricsSnapshot, String> {
    Optional<CityMetricsSnapshot> findFirstByCityIdOrderByTimestampDesc(String cityId);
    List<CityMetricsSnapshot> findByCityIdOrderByTimestampDesc(String cityId);
}
