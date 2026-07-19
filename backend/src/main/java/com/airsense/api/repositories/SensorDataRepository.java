package com.airsense.api.repositories;

import com.airsense.api.entities.SensorData;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SensorDataRepository extends MongoRepository<SensorData, String> {

    List<SensorData> findByTimestampBetween(Instant start, Instant end);
    
    List<SensorData> findByTimestampBetween(Instant start, Instant end, Sort sort);

    @Query(value = "{ 'sensorId': ?0 }", sort = "{ 'timestamp': -1 }")
    List<SensorData> findLatestBySensorId(String sensorId);
    
    Optional<SensorData> findFirstBySensorIdOrderByTimestampDesc(String sensorId);
    
    List<SensorData> findByWardId(String wardId);

    List<SensorData> findBySensorIdAndTimestampBetween(String sensorId, Instant start, Instant end);

    // Get the latest reading for each sensor.
    // For large collections, an aggregation pipeline is better, but Spring Data Mongo can use aggregation.
    // Since we only have a few sensors, we can just find them individually or query all recent.
}
