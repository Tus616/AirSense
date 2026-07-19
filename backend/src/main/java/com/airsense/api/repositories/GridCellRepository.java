package com.airsense.api.repositories;

import com.airsense.api.entities.GridCell;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GridCellRepository extends MongoRepository<GridCell, String> {
    List<GridCell> findByCityId(String cityId);
    void deleteByCityId(String cityId);
}
