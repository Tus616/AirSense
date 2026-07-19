package com.airsense.api.repositories;

import com.airsense.api.entities.ForecastRetrainingRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ForecastRetrainingRequestRepository extends MongoRepository<ForecastRetrainingRequest, String> {
    List<ForecastRetrainingRequest> findByStationKeyAndModelScopeAndHorizonHoursAndStatusIn(
            String stationKey, String modelScope, Integer horizonHours, Collection<String> statuses);
}
