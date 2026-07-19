package com.airsense.api.repositories;

import com.airsense.api.entities.EnforcementRecommendation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EnforcementRecommendationRepository extends MongoRepository<EnforcementRecommendation, String> {
    List<EnforcementRecommendation> findByCityIdOrderByPriorityScoreDesc(String cityId);
    List<EnforcementRecommendation> findByCityIdAndStatusOrderByPriorityScoreDesc(String cityId, EnforcementRecommendation.Status status);
}
