package com.airsense.api.repositories;

import com.airsense.api.entities.Prediction;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PredictionRepository extends MongoRepository<Prediction, String> {
    Optional<Prediction> findTopByWardIdOrderByGeneratedAtDesc(String wardId);
    Optional<Prediction> findTopBySensorIdOrderByGeneratedAtDesc(String sensorId);
    void deleteByWardId(String wardId);
}
