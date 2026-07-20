package com.airsense.api.repositories;

import com.airsense.api.entities.IndustrialSource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IndustrialSourceRepository extends MongoRepository<IndustrialSource, String> {
    List<IndustrialSource> findByWardId(String wardId);
}
