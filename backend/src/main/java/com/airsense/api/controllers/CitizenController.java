package com.airsense.api.controllers;

import com.airsense.api.dto.CitizenProfileDto;
import com.airsense.api.entities.CitizenNotification;
import com.airsense.api.entities.CitizenRiskAdvisory;
import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.User;
import com.airsense.api.entities.VulnerabilityMapping;
import com.airsense.api.repositories.CitizenNotificationRepository;
import com.airsense.api.repositories.CitizenRiskAdvisoryRepository;
import com.airsense.api.repositories.PredictionRepository;
import com.airsense.api.repositories.UserRepository;
import com.airsense.api.repositories.VulnerabilityMappingRepository;
import com.airsense.api.security.UserPrincipal;
import com.airsense.api.services.CitizenAdvisoryService;
import com.airsense.api.services.HealthRiskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/citizen")
public class CitizenController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PredictionRepository predictionRepository;
    
    @Autowired
    private VulnerabilityMappingRepository vulnerabilityMappingRepository;
    
    @Autowired
    private HealthRiskService healthRiskService;
    
    @Autowired
    private CitizenAdvisoryService citizenAdvisoryService;
    
    @Autowired
    private CitizenRiskAdvisoryRepository advisoryRepository;

    @Autowired
    private CitizenNotificationRepository notificationRepository;

    private static final String DEMO_USER_ID = "rahul@gmail.com";

    @GetMapping("/profile")
    public ResponseEntity<CitizenProfileDto> getProfile(Authentication authentication) {
        return ResponseEntity.ok(CitizenProfileDto.from(resolveUser(authentication)));
    }

    @PutMapping("/profile")
    public ResponseEntity<CitizenProfileDto> updateProfile(@RequestBody User updatedProfile, Authentication authentication) {
        User existing = resolveUser(authentication);
        existing.setWardId(updatedProfile.getWardId() != null ? updatedProfile.getWardId() : existing.getWardId());
        existing.setCityId(updatedProfile.getCityId() != null ? updatedProfile.getCityId() : existing.getCityId());
        existing.setSelectedLanguage(updatedProfile.getSelectedLanguage());
        existing.setVulnerable(updatedProfile.getVulnerable());
        existing.setConditions(updatedProfile.getConditions());
        existing.setChannels(updatedProfile.getChannels());
        return ResponseEntity.ok(CitizenProfileDto.from(userRepository.save(existing)));
    }

    @GetMapping("/risk")
    public ResponseEntity<CitizenRiskAdvisory> getRisk(@RequestParam(required = false) String wardId, Authentication authentication) {
        User user = resolveUser(authentication);
        String targetWard = wardId != null ? wardId : (user != null ? user.getWardId() : "W01");
        String targetCity = user != null && user.getCityId() != null ? user.getCityId() : "UNKNOWN_PLACE";
        String language = user != null && user.getSelectedLanguage() != null ? user.getSelectedLanguage() : "en";
        
        // Find latest forecast
        Prediction latestPrediction = predictionRepository.findAll().stream()
                .filter(p -> targetCity.equals(p.getCityId()))
                .sorted((p1, p2) -> p2.getGeneratedAt().compareTo(p1.getGeneratedAt()))
                .findFirst().orElse(null);
                
        int currentAqi = 0;
        int peakAqi = 0;
        String peakTime = "Unavailable";
        
        if (latestPrediction != null && latestPrediction.getPredictions() != null && !latestPrediction.getPredictions().isEmpty()) {
            currentAqi = latestPrediction.getPredictions().get(0).getPredictedAqi();
            peakAqi = latestPrediction.getPredictions().stream().mapToInt(Prediction.HourlyPrediction::getPredictedAqi).max().orElse(currentAqi);
            peakTime = latestPrediction.getPredictions().get(latestPrediction.getPredictions().size() / 2).getTimestamp();
        }

        VulnerabilityMapping mapping = vulnerabilityMappingRepository.findByWardIdAndCityId(targetWard, targetCity).orElse(null);
        
        Map<String, Object> riskResults = healthRiskService.calculateRisk(currentAqi, mapping, user);
        double wardRiskScore = (double) riskResults.get("wardRiskScore");
        double personalRiskScore = (double) riskResults.get("personalRiskScore");
        String riskLevel = (String) riskResults.get("riskLevel");
        
        Map<String, Object> vulnFactors = Map.of(
            "hasHospitals", mapping != null && mapping.getHospitalsCount() > 0,
            "hasSchools", mapping != null && mapping.getSchoolsCount() > 0,
            "asthmaPrevalence", mapping != null ? mapping.getAsthmaPrevalenceEstimate() : 0.0
        );

        CitizenRiskAdvisory advisory = citizenAdvisoryService.generateAdvisory(
            DEMO_USER_ID, targetCity, targetWard, language, currentAqi, peakAqi, peakTime,
            wardRiskScore, personalRiskScore, riskLevel, vulnFactors
        );

        return ResponseEntity.ok(advisory);
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<CitizenNotification>> getNotifications(Authentication authentication) {
        User user = resolveUser(authentication);
        return ResponseEntity.ok(notificationRepository.findTop10ByUserIdOrderByGeneratedAtDesc(user.getEmail()));
    }

    private User resolveUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            Optional<User> byId = userRepository.findById(principal.getId());
            if (byId.isPresent()) {
                return ensureCitizenDefaults(byId.get());
            }
        }

        return ensureCitizenDefaults(userRepository.findByEmail(DEMO_USER_ID)
                .orElseGet(() -> userRepository.save(User.builder()
                        .email(DEMO_USER_ID)
                        .name("Rahul Citizen")
                        .role("CITIZEN")
                        .build())));
    }

    private User ensureCitizenDefaults(User user) {
        boolean changed = false;
        if (user.getWardId() == null) {
            user.setWardId("W01");
            changed = true;
        }
        if (user.getCityId() == null) {
            user.setCityId("UNKNOWN_PLACE");
            changed = true;
        }
        if (user.getSelectedLanguage() == null) {
            user.setSelectedLanguage("en");
            changed = true;
        }
        if (user.getVulnerable() == null) {
            user.setVulnerable(false);
            changed = true;
        }
        if (user.getConditions() == null) {
            user.setConditions(List.of());
            changed = true;
        }
        if (user.getChannels() == null) {
            user.setChannels(List.of("inApp"));
            changed = true;
        }
        return changed ? userRepository.save(user) : user;
    }
}
