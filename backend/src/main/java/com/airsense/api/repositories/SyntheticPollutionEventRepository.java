package com.airsense.api.repositories;

import com.airsense.api.entities.SyntheticPollutionEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface SyntheticPollutionEventRepository extends MongoRepository<SyntheticPollutionEvent, String> {
    List<SyntheticPollutionEvent> findByCityIdAndWardId(String cityId, String wardId);
    boolean existsByEventId(String eventId);
}
