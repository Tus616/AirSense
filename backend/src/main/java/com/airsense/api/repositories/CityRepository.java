package com.airsense.api.repositories;

import com.airsense.api.entities.City;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CityRepository extends MongoRepository<City, String> {
    Optional<City> findByCityId(String cityId);
    Optional<City> findByIsHomeCityTrue();
}
