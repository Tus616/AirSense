package com.airsense.api.services;

import com.airsense.api.entities.*;
import com.airsense.api.repositories.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

@Slf4j
@Service
public class CitySeedService {

    @Autowired private CityRepository cityRepository;
    @Autowired private SensorDataRepository sensorDataRepository;
    @Autowired private PredictionRepository predictionRepository;
    @Autowired private AttributionResultRepository attributionResultRepository;
    @Autowired private EnforcementRecommendationRepository enforcementRepository;
    @Autowired private AdvisoryRepository advisoryRepository;
    @Autowired private MongoTemplate mongoTemplate;

    public void seedAndBackfill() {
        backfillExistingData();
        seedCities();
        seedSyntheticData("MUMBAI");
        seedSyntheticData("BENGALURU");
    }

    private void backfillExistingData() {
        log.info("Backfilling existing data with default city DELHI...");
        
        // SensorData
        Update updateSensor = new Update().set("cityId", "DELHI").set("cityName", "Delhi");
        mongoTemplate.updateMulti(Query.query(Criteria.where("cityId").exists(false)), updateSensor, SensorData.class);

        // Prediction
        Update updatePred = new Update().set("cityId", "DELHI").set("cityName", "Delhi");
        mongoTemplate.updateMulti(Query.query(Criteria.where("cityId").exists(false)), updatePred, Prediction.class);

        // AttributionResult
        Update updateAttr = new Update().set("cityId", "DELHI").set("cityName", "Delhi");
        mongoTemplate.updateMulti(Query.query(Criteria.where("cityId").exists(false)), updateAttr, AttributionResult.class);

        // EnforcementRecommendation
        Update updateEnf = new Update().set("cityId", "DELHI").set("cityName", "Delhi");
        mongoTemplate.updateMulti(Query.query(Criteria.where("cityId").exists(false)), updateEnf, EnforcementRecommendation.class);

        // Advisory
        Update updateAdv = new Update().set("cityId", "DELHI").set("cityName", "Delhi");
        mongoTemplate.updateMulti(Query.query(Criteria.where("cityId").exists(false)), updateAdv, Advisory.class);
    }

    private void seedCities() {
        if (cityRepository.findByCityId("DELHI").isEmpty()) {
            cityRepository.save(City.builder()
                .cityId("DELHI")
                .cityName("Delhi")
                .state("Delhi")
                .country("India")
                .timezone("Asia/Kolkata")
                .center(new GeoJsonPoint(77.2090, 28.6139))
                .population(30000000)
                .isHomeCity(true)
                .dataSource("OBSERVED")
                .build());
        }
        if (cityRepository.findByCityId("MUMBAI").isEmpty()) {
            cityRepository.save(City.builder()
                .cityId("MUMBAI")
                .cityName("Mumbai")
                .state("Maharashtra")
                .country("India")
                .timezone("Asia/Kolkata")
                .center(new GeoJsonPoint(72.8777, 19.0760))
                .population(20000000)
                .isHomeCity(false)
                .dataSource("SYNTHETIC_DEMO")
                .build());
        }
        if (cityRepository.findByCityId("BENGALURU").isEmpty()) {
            cityRepository.save(City.builder()
                .cityId("BENGALURU")
                .cityName("Bengaluru")
                .state("Karnataka")
                .country("India")
                .timezone("Asia/Kolkata")
                .center(new GeoJsonPoint(77.5946, 12.9716))
                .population(12000000)
                .isHomeCity(false)
                .dataSource("SYNTHETIC_DEMO")
                .build());
        }
    }

    private void seedSyntheticData(String cityId) {
        // Only seed if no sensor data exists for this city
        if (mongoTemplate.exists(Query.query(Criteria.where("cityId").is(cityId)), SensorData.class)) {
            return;
        }

        log.info("Seeding synthetic data for {}", cityId);
        String cityName = cityId.equals("MUMBAI") ? "Mumbai" : "Bengaluru";
        int baseAqi = cityId.equals("MUMBAI") ? 150 : 85;
        double pm25 = cityId.equals("MUMBAI") ? 65.0 : 40.0;
        String domSource = cityId.equals("MUMBAI") ? "industrial" : "traffic";

        Instant now = Instant.now();

        // Seed 30 days of sensor data (1 per day for simplicity to show trend)
        for (int i = 30; i >= 0; i--) {
            int aqiVar = ((i % 9) - 4) * 4;
            SensorData data = SensorData.builder()
                .cityId(cityId)
                .cityName(cityName)
                .timestamp(now.minus(i, ChronoUnit.DAYS))
                .sensorId("SYN-" + cityId + "-1")
                .stationName("Central " + cityName)
                .pollutants(SensorData.Pollutants.builder().aqi(baseAqi + aqiVar).pm25(pm25).build())
                .build();
            sensorDataRepository.save(data);
        }

        // Seed Prediction with RMSE
        Prediction pred = Prediction.builder()
            .cityId(cityId)
            .cityName(cityName)
            .sensorId("SYN-" + cityId + "-1")
            .generatedAt(now.toString())
            .rmse(12.5)
            .baselineRmse(20.0)
            .build();
        predictionRepository.save(pred);

        // Seed Attribution
        AttributionResult attr = AttributionResult.builder()
            .cityId(cityId)
            .cityName(cityName)
            .timestamp(now)
            .rankedSources(List.of(
                AttributionResult.RankedSource.builder().category(domSource).confidence(85.0).build()
            ))
            .build();
        attributionResultRepository.save(attr);

        // Seed Enforcement
        for (int i = 0; i < 5; i++) {
            EnforcementRecommendation rec = new EnforcementRecommendation();
            rec.setRecommendationId("SYN-REC-" + UUID.randomUUID().toString().substring(0, 8));
            rec.setCityId(cityId);
            rec.setCityName(cityName);
            rec.setGeneratedAt(now);
            rec.setActionType("INSPECT_INDUSTRIAL_UNIT");
            rec.setTarget("Factory " + i);
            rec.setPriorityScore(85.0);
            rec.setExpectedImpact("Reduction of peak AQI by approx 10-15%");
            rec.setEvidence(List.of(new EnforcementRecommendation.EvidenceItem("POLLUTER", "F-"+i, "Emissions exceed limit")));
            rec.setStatus(i < 3 ? EnforcementRecommendation.Status.RESOLVED : EnforcementRecommendation.Status.OPEN);
            enforcementRepository.save(rec);
        }

        // Seed Advisory
        Advisory adv = Advisory.builder()
            .cityId(cityId)
            .cityName(cityName)
            .generatedAt(now.toString())
            .status("GENERATED")
            .build();
        advisoryRepository.save(adv);
        
        // Seed CitizenRiskAdvisories for language coverage
        String[] langs = {"en", "hi", "kn", "ta"};
        for (String l : langs) {
            com.airsense.api.entities.CitizenRiskAdvisory cra = com.airsense.api.entities.CitizenRiskAdvisory.builder()
                .cityId(cityId)
                .wardId("W01")
                .language(l)
                .forecastPeakAqi(150)
                .riskLevel("Moderate")
                .build();
            mongoTemplate.save(cra);
        }
    }
}
