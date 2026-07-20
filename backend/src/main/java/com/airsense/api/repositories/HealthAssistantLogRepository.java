package com.airsense.api.repositories;

import com.airsense.api.entities.HealthAssistantLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HealthAssistantLogRepository extends MongoRepository<HealthAssistantLog, String> {
}
