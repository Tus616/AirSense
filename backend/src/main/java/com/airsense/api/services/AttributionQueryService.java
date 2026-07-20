package com.airsense.api.services;

import com.airsense.api.entities.AttributionResult;
import com.airsense.api.repositories.AttributionResultRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AttributionQueryService {

    @Autowired
    private AttributionResultRepository repository;

    public Optional<AttributionResult> getLatestForWard(String wardId) {
        return repository.findTopByWardIdOrderByTimestampDesc(wardId);
    }

    public List<AttributionResult> getHistoryForWard(String wardId, Instant from, Instant to) {
        return repository.findByWardIdAndTimestampBetweenOrderByTimestampDesc(wardId, from, to);
    }
}
