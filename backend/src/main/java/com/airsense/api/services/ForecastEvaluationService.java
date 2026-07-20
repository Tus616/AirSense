package com.airsense.api.services;

import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.SensorData;
import com.airsense.api.entities.EvaluationMetrics.ForecastMetrics;
import com.airsense.api.repositories.PredictionRepository;
import com.airsense.api.repositories.SensorDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ForecastEvaluationService {

    @Autowired
    private PredictionRepository predictionRepository;

    @Autowired
    private SensorDataRepository sensorDataRepository;

    public ForecastMetrics evaluate() {
        List<Prediction> predictions = predictionRepository.findAll();
        double modelSquaredError = 0.0;
        double persistenceSquaredError = 0.0;
        double modelAbsoluteError = 0.0;
        double persistenceAbsoluteError = 0.0;
        int evaluated = 0;

        for (Prediction prediction : predictions) {
            if (prediction.getPredictions() == null || prediction.getPredictions().isEmpty() || prediction.getSensorId() == null) {
                continue;
            }
            Integer baseline = baselineAqi(prediction);
            if (baseline == null || baseline <= 0) {
                continue;
            }
            for (Prediction.HourlyPrediction point : prediction.getPredictions()) {
                if (point.getPredictedAqi() == null || point.getPredictedAqi() <= 0 || point.getTimestamp() == null) {
                    continue;
                }
                Instant targetTime = parse(point.getTimestamp());
                if (targetTime == null || targetTime.isAfter(Instant.now())) {
                    continue;
                }
                Integer observed = observedAqi(prediction.getSensorId(), targetTime);
                if (observed == null || observed <= 0) {
                    continue;
                }
                double modelError = point.getPredictedAqi() - observed;
                double persistenceError = baseline - observed;
                modelSquaredError += modelError * modelError;
                persistenceSquaredError += persistenceError * persistenceError;
                modelAbsoluteError += Math.abs(modelError);
                persistenceAbsoluteError += Math.abs(persistenceError);
                evaluated++;
            }
        }

        double modelRmse = evaluated > 0 ? Math.sqrt(modelSquaredError / evaluated) : 0.0;
        double persistenceRmse = evaluated > 0 ? Math.sqrt(persistenceSquaredError / evaluated) : 0.0;
        double improvement = persistenceRmse > 0 ? ((persistenceRmse - modelRmse) / persistenceRmse) * 100.0 : 0.0;
        return ForecastMetrics.builder()
                .modelRmse(round(modelRmse))
                .modelMae(round(evaluated > 0 ? modelAbsoluteError / evaluated : 0.0))
                .persistenceRmse(round(persistenceRmse))
                .persistenceMae(round(evaluated > 0 ? persistenceAbsoluteError / evaluated : 0.0))
                .improvementPct(round(improvement))
                .evaluatedCellHours(evaluated)
                .build();
    }

    private Integer baselineAqi(Prediction prediction) {
        Instant generatedAt = parse(prediction.getGeneratedAt());
        if (generatedAt == null) {
            return null;
        }
        return observedAqi(prediction.getSensorId(), generatedAt);
    }

    private Integer observedAqi(String sensorId, Instant targetTime) {
        List<SensorData> candidates = sensorDataRepository.findBySensorIdAndTimestampBetween(
                sensorId,
                targetTime.minus(Duration.ofMinutes(90)),
                targetTime.plus(Duration.ofMinutes(90))
        );
        Optional<SensorData> closest = candidates.stream()
                .filter(item -> item.getPollutants() != null && item.getPollutants().getAqi() != null)
                .min((left, right) -> Long.compare(
                        Math.abs(Duration.between(left.getTimestamp(), targetTime).toMillis()),
                        Math.abs(Duration.between(right.getTimestamp(), targetTime).toMillis())
                ));
        return closest.map(item -> item.getPollutants().getAqi()).orElse(null);
    }

    private Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.endsWith("Z") ? value : value + "Z");
        } catch (Exception ignored) {
            return null;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
