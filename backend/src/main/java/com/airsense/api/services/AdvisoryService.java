package com.airsense.api.services;

import com.airsense.api.entities.Advisory;
import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.SensorData;
import com.airsense.api.repositories.AdvisoryRepository;
import com.airsense.api.repositories.SensorDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
public class AdvisoryService {

    @Autowired
    private AdvisoryRepository advisoryRepository;

    @Autowired
    private SensorDataRepository sensorDataRepository;

    @Autowired
    private AttributionService attributionService;

    @Autowired
    private GeminiClient geminiClient;

    public void generateAdvisoryForPrediction(Prediction prediction) {
        log.info("Generating advisory for ward {} based on prediction {}", prediction.getWardId(), prediction.getId());

        // Extract peak AQI over next 48 hours (first 48 elements of prediction list)
        int peakAqi = 0;
        String peakCategory = "Unknown";
        int limit = Math.min(48, prediction.getPredictions().size());
        
        if (limit == 0) {
            log.warn("No hourly predictions found. Skipping advisory generation.");
            return;
        }

        for (int i = 0; i < limit; i++) {
            Prediction.HourlyPrediction hp = prediction.getPredictions().get(i);
            if (hp.getPredictedAqi() > peakAqi) {
                peakAqi = hp.getPredictedAqi();
                peakCategory = hp.getCategory();
            }
        }

        // Get deterministic primary source
        String primarySource = attributionService.determinePrimarySource(prediction);

        // Get latest weather from SensorData
        Optional<SensorData> latestDataOpt = sensorDataRepository.findFirstBySensorIdOrderByTimestampDesc(prediction.getSensorId());
        
        double windSpeed = 5.0;
        double windDirection = 0.0;
        double temp = 25.0;
        double humidity = 50.0;
        Object weatherSnapshot = null;

        if (latestDataOpt.isPresent() && latestDataOpt.get().getWeather() != null) {
            SensorData.Weather weather = latestDataOpt.get().getWeather();
            windSpeed = weather.getWindSpeed() != null ? weather.getWindSpeed() : windSpeed;
            windDirection = weather.getWindDirection() != null ? weather.getWindDirection() : windDirection;
            temp = weather.getTemperature() != null ? weather.getTemperature() : temp;
            humidity = weather.getHumidity() != null ? weather.getHumidity() : humidity;
            weatherSnapshot = weather;
        }

        // Build grounded prompt
        String prompt = String.format(
            "You are an expert environmental public health official. The predicted AQI for Ward %s over the next 48 hours will peak at %d (%s). The primary attributed source is %s. Current weather: wind %.1f km/h from %.1f, temperature %.1f C, humidity %.1f%%. Generate: (1) a brief authoritative directive for the municipal enforcement team, and (2) a separate empathetic 3-sentence health advisory for citizens.",
            prediction.getWardId(), peakAqi, peakCategory, primarySource, windSpeed, windDirection, temp, humidity
        );

        String promptHash = String.valueOf(prompt.hashCode());

        // Call Gemini
        GeminiClient.AdvisoryResult result = geminiClient.generateAdvisory(prompt);

        // Persist to MongoDB
        Advisory advisory = Advisory.builder()
                .wardId(prediction.getWardId())
                .predictionId(prediction.getId())
                .generatedAt(Instant.now().toString())
                .forecastWindowHours(48)
                .peakAqi(peakAqi)
                .category(peakCategory)
                .primarySource(primarySource)
                .weatherSnapshot(weatherSnapshot)
                .municipalDirective(result.municipalDirective)
                .citizenAdvisory(result.citizenAdvisory)
                .model("gemini-2.5-pro")
                .promptHash(promptHash)
                .status(result.status)
                .errorMessage(result.errorMessage)
                .build();

        // Ensure only one advisory per ward is kept to prevent bloat
        advisoryRepository.deleteByWardId(prediction.getWardId());
        advisoryRepository.save(advisory);
        
        log.info("Advisory saved for ward {}. Status: {}", prediction.getWardId(), result.status);
    }
}
