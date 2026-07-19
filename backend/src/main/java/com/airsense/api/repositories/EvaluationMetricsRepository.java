package com.airsense.api.repositories;

import com.airsense.api.entities.EvaluationMetrics;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface EvaluationMetricsRepository extends MongoRepository<EvaluationMetrics, String> {
    Optional<EvaluationMetrics> findFirstByOrderByGeneratedAtDesc();
}
