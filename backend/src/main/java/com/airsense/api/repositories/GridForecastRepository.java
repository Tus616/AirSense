package com.airsense.api.repositories;

import com.airsense.api.entities.GridForecast;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GridForecastRepository extends MongoRepository<GridForecast, String> {
    Optional<GridForecast> findFirstByGridIdOrderByGeneratedAtDesc(String gridId);
    List<GridForecast> findByCityIdOrderByGeneratedAtDesc(String cityId);
}
