package com.airsense.api.services;

import com.airsense.api.entities.EnforcementRecommendation;
import com.airsense.api.entities.GridForecast;
import com.airsense.api.entities.Prediction;
import com.airsense.api.entities.SensorData;
import com.airsense.api.entities.IndustrialSource;
import com.airsense.api.entities.ConstructionPermit;
import com.airsense.api.entities.AttributionResult;
import com.airsense.api.repositories.EnforcementRecommendationRepository;
import com.airsense.api.repositories.SensorDataRepository;
import com.airsense.api.repositories.IndustrialSourceRepository;
import com.airsense.api.repositories.ConstructionPermitRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class EnforcementIntelligenceService {

    @Autowired
    private EnforcementRecommendationRepository enforcementRepository;

    @Autowired
    private SensorDataRepository sensorDataRepository;

    @Autowired
    private AttributionQueryService attributionQueryService;

    @Autowired
    private IndustrialSourceRepository industrialSourceRepository;

    @Autowired
    private ConstructionPermitRepository constructionPermitRepository;

    @Autowired
    private GeminiClient geminiClient;

    public void generateEnforcementActions(List<Prediction> latestPredictions, List<GridForecast> latestGrids) {
        log.info("Starting Enforcement Intelligence Agent deterministic generation...");

        for (Prediction prediction : latestPredictions) {
            try {
                processPrediction(prediction);
            } catch (Exception e) {
                log.error("Failed to generate enforcement action for ward {}: {}", prediction.getWardId(), e.getMessage());
            }
        }
    }

    private void processPrediction(Prediction prediction) {
        // 1. Severity Score (0-100)
        int peakAqi = 0;
        int hoursToPeak = -1;
        for (int i = 0; i < Math.min(72, prediction.getPredictions().size()); i++) {
            Prediction.HourlyPrediction hp = prediction.getPredictions().get(i);
            if (hp.getPredictedAqi() > peakAqi) {
                peakAqi = hp.getPredictedAqi();
                hoursToPeak = i;
            }
        }

        if (peakAqi < 150) {
            return;
        }

        double severityScore = Math.min(100.0, (peakAqi / 500.0) * 100.0);
        double urgencyScore = (hoursToPeak >= 0) ? Math.max(0.0, 100.0 - (hoursToPeak * (100.0 / 72.0))) : 0.0;
        
        // Proxy for population exposure (0-1)
        double popExposure = 0.85;

        // Fetch attribution result for ward
        Optional<AttributionResult> attrOpt = attributionQueryService.getLatestForWard(prediction.getWardId());
        if (attrOpt.isEmpty()) {
            log.warn("No AttributionResult found for ward {}, skipping enforcement logic", prediction.getWardId());
            return;
        }
        AttributionResult attribution = attrOpt.get();

        // Cross-reference data
        List<IndustrialSource> industries = industrialSourceRepository.findByWardId(prediction.getWardId());
        List<ConstructionPermit> permits = constructionPermitRepository.findByWardId(prediction.getWardId());

        List<EnforcementRecommendation> actions = new ArrayList<>();

        for (AttributionResult.RankedSource source : attribution.getRankedSources()) {
            double sourceConf = source.getConfidence(); // 0-100
            
            if (source.getCategory().contains("industrial") || source.getCategory().contains("thermal")) {
                for (IndustrialSource ind : industries) {
                    double priorityScore = (sourceConf / 100.0) * severityScore * popExposure;
                    
                    EnforcementRecommendation rec = createBaseRecommendation(prediction, "INSPECT_INDUSTRIAL_UNIT", ind.getFacilityId(), priorityScore, sourceConf);
                    
                    rec.getEvidence().add(new EnforcementRecommendation.EvidenceItem("POLLUTER", ind.getFacilityId(), 
                        String.format("Industrial Facility: %s, Category: %s, Compliance: %s", ind.getFacilityName(), ind.getCategory(), ind.getComplianceStatus())));
                    
                    if (ind.getLocation() != null) {
                        rec.getEvidence().add(new EnforcementRecommendation.EvidenceItem("GEOSPATIAL", ind.getFacilityId(),
                            String.format("Lat: %f, Lon: %f", ind.getLocation().getY(), ind.getLocation().getX())));
                    }

                    String prompt = String.format("You are an AI for municipal enforcement. Generate a short 2-sentence justification for action INSPECT_INDUSTRIAL_UNIT targeting facility %s (%s). Mention its compliance status is %s and attribution confidence is %.1f%%.", ind.getFacilityId(), ind.getFacilityName(), ind.getComplianceStatus(), sourceConf);
                    generateAndAddJustification(rec, prompt);
                    
                    actions.add(rec);
                }
            } else if (source.getCategory().contains("construction") || source.getCategory().contains("dust")) {
                for (ConstructionPermit permit : permits) {
                    double priorityScore = (sourceConf / 100.0) * severityScore * popExposure;
                    
                    EnforcementRecommendation rec = createBaseRecommendation(prediction, "DUST_SUPPRESSION", permit.getPermitId(), priorityScore, sourceConf);
                    
                    rec.getEvidence().add(new EnforcementRecommendation.EvidenceItem("PERMIT", permit.getPermitId(), 
                        String.format("Project: %s, Contractor: %s, Dust Risk: %s", permit.getProjectType(), permit.getContractor(), permit.getDustRiskLevel())));
                    
                    if (permit.getPolygon() != null && !permit.getPolygon().getCoordinates().isEmpty() && !permit.getPolygon().getCoordinates().get(0).getCoordinates().isEmpty()) {
                         var point = permit.getPolygon().getCoordinates().get(0).getCoordinates().get(0);
                         rec.getEvidence().add(new EnforcementRecommendation.EvidenceItem("GEOSPATIAL", permit.getPermitId(),
                             String.format("Lat: %f, Lon: %f (Polygon edge)", point.getY(), point.getX())));
                    }

                    String prompt = String.format("You are an AI for municipal enforcement. Generate a short 2-sentence justification for action DUST_SUPPRESSION targeting construction permit %s (Contractor: %s). Mention dust risk is %s and attribution confidence is %.1f%%.", permit.getPermitId(), permit.getContractor(), permit.getDustRiskLevel(), sourceConf);
                    generateAndAddJustification(rec, prompt);
                    
                    actions.add(rec);
                }
            } else if (source.getCategory().contains("traffic")) {
                double priorityScore = (sourceConf / 100.0) * severityScore * popExposure;
                EnforcementRecommendation rec = createBaseRecommendation(prediction, "RESTRICT_HEAVY_VEHICLES", "Ward Corridor " + prediction.getWardId(), priorityScore, sourceConf);
                rec.getEvidence().add(new EnforcementRecommendation.EvidenceItem("TRAFFIC", "TRAFFIC_API", "Heavy vehicle density in ward is elevated"));
                
                String prompt = String.format("You are an AI for municipal enforcement. Generate a short 2-sentence justification for action RESTRICT_HEAVY_VEHICLES in ward %s. Mention attribution confidence is %.1f%%.", prediction.getWardId(), sourceConf);
                generateAndAddJustification(rec, prompt);
                
                actions.add(rec);
            }
        }

        if (actions.isEmpty()) {
            // Default generic action if no specific targets
            double priorityScore = severityScore * 0.5;
            EnforcementRecommendation rec = createBaseRecommendation(prediction, "PUBLIC_WORKS_CLEANING", "Ward " + prediction.getWardId(), priorityScore, 50.0);
            rec.getEvidence().add(new EnforcementRecommendation.EvidenceItem("FORECAST", prediction.getId(), "General elevation of AQI without specific point sources."));
            generateAndAddJustification(rec, "Generate a 1 sentence generic justification for public works cleaning due to elevated AQI.");
            actions.add(rec);
        }

        enforcementRepository.saveAll(actions);
        log.info("Saved {} Enforcement Recommendations for Ward {}", actions.size(), prediction.getWardId());
    }

    private EnforcementRecommendation createBaseRecommendation(Prediction prediction, String actionType, String target, double priorityScore, double confidence) {
        EnforcementRecommendation rec = new EnforcementRecommendation();
        rec.setRecommendationId("REC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        rec.setCityId("DELHI");
        rec.setWardId(prediction.getWardId());
        rec.setGeneratedAt(Instant.now());
        rec.setActionType(actionType);
        rec.setTarget(target);
        rec.setPriorityScore(priorityScore);
        rec.setExpectedImpact("Reduction of peak AQI by approx 10-15%");
        rec.setConfidence(confidence);
        rec.setEvidence(new ArrayList<>());
        rec.setForecastRef(prediction.getId());
        rec.setStatus(EnforcementRecommendation.Status.OPEN);
        return rec;
    }

    private void generateAndAddJustification(EnforcementRecommendation rec, String prompt) {
        String explanationText = "Action recommended based on deterministic scoring criteria.";
        try {
            GeminiClient.AdvisoryResult result = geminiClient.generateAdvisory(prompt);
            if (result.municipalDirective != null && !result.municipalDirective.isEmpty()) {
                explanationText = result.municipalDirective;
            }
        } catch (Exception e) {
            log.warn("Gemini explanation generation failed: {}", e.getMessage());
        }
        rec.getEvidence().add(new EnforcementRecommendation.EvidenceItem("AI_ANALYSIS", "GEMINI-2.5", explanationText));
    }
}
