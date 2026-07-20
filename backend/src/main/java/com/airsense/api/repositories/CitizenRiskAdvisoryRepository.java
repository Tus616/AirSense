package com.airsense.api.repositories;

import com.airsense.api.entities.CitizenRiskAdvisory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CitizenRiskAdvisoryRepository extends MongoRepository<CitizenRiskAdvisory, String> {
    Optional<CitizenRiskAdvisory> findTopByWardIdAndLanguageOrderByGeneratedAtDesc(String wardId, String language);
    List<CitizenRiskAdvisory> findByUserIdOrderByGeneratedAtDesc(String userId);
}
