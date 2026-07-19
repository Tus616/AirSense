package com.airsense.api.repositories;

import com.airsense.api.entities.ConstructionPermit;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConstructionPermitRepository extends MongoRepository<ConstructionPermit, String> {
    List<ConstructionPermit> findByWardId(String wardId);
}
