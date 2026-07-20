package com.airsense.api.repositories;

import com.airsense.api.entities.Advisory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdvisoryRepository extends MongoRepository<Advisory, String> {
    Optional<Advisory> findTopByWardIdOrderByGeneratedAtDesc(String wardId);
    void deleteByWardId(String wardId);
}
