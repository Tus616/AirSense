package com.airsense.api.repositories;

import com.airsense.api.entities.PolicySimulation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicySimulationRepository extends MongoRepository<PolicySimulation, String> {
    List<PolicySimulation> findAllByOrderByCreatedAtDesc();
}
