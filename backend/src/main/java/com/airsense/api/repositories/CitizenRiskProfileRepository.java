package com.airsense.api.repositories;

import com.airsense.api.entities.CitizenRiskProfile;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CitizenRiskProfileRepository extends MongoRepository<CitizenRiskProfile, String> {
    Optional<CitizenRiskProfile> findByUserId(String userId);
}
