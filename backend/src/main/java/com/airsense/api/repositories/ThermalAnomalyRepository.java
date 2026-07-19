package com.airsense.api.repositories;

import com.airsense.api.entities.ThermalAnomaly;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ThermalAnomalyRepository extends MongoRepository<ThermalAnomaly, String> {
    List<ThermalAnomaly> findByWardId(String wardId);
}
