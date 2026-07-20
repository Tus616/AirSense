package com.airsense.api.services;

import com.airsense.api.entities.CitizenNotification;
import com.airsense.api.repositories.CitizenNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class NotificationDeliveryService {

    @Autowired
    private CitizenNotificationRepository notificationRepository;

    public CitizenNotification deliver(String userId, String message, String language, String channel, String riskCategory, String forecastRef) {
        CitizenNotification notification = CitizenNotification.builder()
                .userId(userId)
                .notificationId(UUID.randomUUID().toString())
                .generatedAt(Instant.now())
                .language(language)
                .channel(channel)
                .message(message)
                .riskCategory(riskCategory)
                .forecastRef(forecastRef)
                .build();

        if ("IN_APP".equalsIgnoreCase(channel)) {
            notification.setStatus("SENT");
            log.info("In-App notification stored for user {}", userId);
        } else {
            try {
                Thread.sleep(50);
                notification.setStatus("SENT");
                log.info("{} notification marked sent for user {}", channel, userId);
            } catch (Exception e) {
                notification.setStatus("FAILED");
                log.error("Failed to send mock {} notification to user {}: {}", channel, userId, e.getMessage());
            }
        }

        return notificationRepository.save(notification);
    }
}
