package com.airsense.api.repositories;

import com.airsense.api.entities.CitizenNotification;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CitizenNotificationRepository extends MongoRepository<CitizenNotification, String> {
    List<CitizenNotification> findByUserIdOrderByGeneratedAtDesc(String userId);
    List<CitizenNotification> findTop10ByUserIdOrderByGeneratedAtDesc(String userId);
}
