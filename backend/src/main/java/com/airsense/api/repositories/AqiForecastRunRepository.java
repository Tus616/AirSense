package com.airsense.api.repositories;

import com.airsense.api.entities.AqiForecastRun;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AqiForecastRunRepository extends MongoRepository<AqiForecastRun, String> {
    List<AqiForecastRun> findByLocationKeyAndGeneratedAtBetween(String locationKey, Instant start, Instant end);

    List<AqiForecastRun> findByLocationKeyInAndGeneratedAtBetween(List<String> locationKeys, Instant start, Instant end);

    List<AqiForecastRun> findByGeneratedAtBetween(Instant start, Instant end);

    List<AqiForecastRun> findByEvaluatedFalseAndTargetTimeBefore(Instant cutoff);

    List<AqiForecastRun> findByLocationKeyAndEvaluatedTrueAndGeneratedAtBetween(String locationKey, Instant start, Instant end);

    List<AqiForecastRun> findByLocationKeyInAndEvaluatedTrueAndGeneratedAtBetween(List<String> locationKeys, Instant start, Instant end);

    List<AqiForecastRun> findByEvaluatedTrueAndGeneratedAtBetween(Instant start, Instant end);
}
