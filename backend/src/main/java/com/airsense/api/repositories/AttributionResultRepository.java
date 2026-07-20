package com.airsense.api.repositories;

import com.airsense.api.entities.AttributionResult;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttributionResultRepository extends MongoRepository<AttributionResult, String> {
    List<AttributionResult> findByWardIdOrderByTimestampDesc(String wardId);
    Optional<AttributionResult> findTopByWardIdOrderByTimestampDesc(String wardId);
    List<AttributionResult> findByWardIdAndTimestampBetweenOrderByTimestampDesc(String wardId, Instant from, Instant to);
}
