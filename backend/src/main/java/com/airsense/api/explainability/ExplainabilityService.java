package com.airsense.api.explainability;

import com.airsense.api.advisory.HealthAdvisory;
import com.airsense.api.advisory.HealthAdvisoryEvidence;
import com.airsense.api.attribution.AttributionEvidence;
import com.airsense.api.attribution.PollutionSourceContribution;
import com.airsense.api.decision.DecisionIntelligenceResult;
import com.airsense.api.decision.EngineStatus;
import com.airsense.api.decision.EvidenceBundle;
import com.airsense.api.decision.PriorityAction;
import com.airsense.api.enforcement.EnforcementEvidence;
import com.airsense.api.enforcement.EnforcementRecommendation;
import com.airsense.api.forecast.ForecastExplanation;
import com.airsense.api.forecast.ForecastPoint;
import com.airsense.api.geospatial.GeoSpatialSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class ExplainabilityService {

    public ExplainabilityResult explain(DecisionIntelligenceResult decision) {
        DecisionIntelligenceResult safeDecision = decision != null ? decision : DecisionIntelligenceResult.builder().build();
        Instant generatedAt = Instant.now();
        ConfidenceBreakdown confidence = confidenceBreakdown(safeDecision);
        List<EvidenceItem> evidence = aggregateEvidence(safeDecision, generatedAt);
        List<ReasoningStep> reasoning = reasoning(safeDecision, confidence);
        List<ModelExplanation> modelExplanations = modelExplanations(safeDecision, confidence);
        List<String> datasets = datasets(safeDecision, evidence);
        Map<String, String> providerStatus = providerStatus(safeDecision);
        List<String> limitations = limitations(safeDecision, confidence, providerStatus, evidence);
        double explainabilityScore = explainabilityScore(reasoning, evidence, datasets, limitations, safeDecision);
        String narrative = narrative(safeDecision, reasoning, limitations);

        log.info("Explainability Success cityId={} confidence={} evidence={} reasoningSteps={}",
                value(safeDecision.getCityId(), "UNKNOWN"), confidence.getOverall(), evidence.size(), reasoning.size());

        return ExplainabilityResult.builder()
                .decisionId(decisionId(safeDecision))
                .city(safeDecision.getCity())
                .cityId(safeDecision.getCityId())
                .generatedAt(generatedAt)
                .snapshotId(safeDecision.getSnapshotId())
                .locationHash(safeDecision.getLocationHash())
                .overallConfidence(confidence.getOverall())
                .confidenceBreakdown(confidence)
                .reasoning(reasoning)
                .evidence(evidence)
                .datasets(datasets)
                .providerStatus(providerStatus)
                .limitations(limitations)
                .explanation(narrative)
                .explainabilityScore(explainabilityScore)
                .modelExplanations(modelExplanations)
                .build();
    }

    public ExplainabilitySummary summarize(ExplainabilityResult result) {
        if (result == null) {
            return ExplainabilitySummary.builder()
                    .explainabilityScore(0.0)
                    .overallConfidence(0.0)
                    .evidenceCount(0)
                    .reasoningStepCount(0)
                    .degradedMode(true)
                    .build();
        }
        return ExplainabilitySummary.builder()
                .explainabilityScore(result.getExplainabilityScore())
                .overallConfidence(result.getOverallConfidence())
                .evidenceCount(result.getEvidence() != null ? result.getEvidence().size() : 0)
                .reasoningStepCount(result.getReasoning() != null ? result.getReasoning().size() : 0)
                .degradedMode(result.getLimitations() != null && !result.getLimitations().isEmpty())
                .build();
    }

    private ConfidenceBreakdown confidenceBreakdown(DecisionIntelligenceResult decision) {
        EvidenceBundle evidence = decision.getEvidenceBundle();
        Map<String, Double> scores = evidence != null && evidence.getConfidenceScores() != null
                ? evidence.getConfidenceScores()
                : Map.of();
        Map<String, String> providerStatus = providerStatus(decision);
        double forecast = firstPositive(number(scores.get("forecast")), decision.getForecast() != null ? decision.getForecast().getOverallConfidence() : 0.0);
        double attribution = firstPositive(number(scores.get("attribution")), decision.getAttribution() != null ? decision.getAttribution().getOverallConfidence() : 0.0);
        double enforcement = firstPositive(number(scores.get("enforcement")), confidenceFromRecommendations(decision));
        double advisory = firstPositive(number(scores.get("advisory")), confidenceFromAdvisories(decision));
        double weather = confidenceFromProvider(providerStatus.get("weather"));
        double aqi = confidenceFromProvider(providerStatus.get("aqi"));
        double satellite = confidenceFromProvider(providerStatus.get("satellite"));
        double overall = firstPositive(decision.getOverallConfidence(), average(List.of(weather, aqi, satellite, forecast, attribution, enforcement, advisory)));

        return ConfidenceBreakdown.builder()
                .weather(round(weather))
                .aqi(round(aqi))
                .satellite(round(satellite))
                .forecast(round(forecast))
                .attribution(round(attribution))
                .enforcement(round(enforcement))
                .citizenAdvisory(round(advisory))
                .overall(round(overall))
                .build();
    }

    private List<EvidenceItem> aggregateEvidence(DecisionIntelligenceResult decision, Instant timestamp) {
        List<EvidenceItem> evidence = new ArrayList<>();
        EvidenceBundle bundle = decision.getEvidenceBundle();
        if (bundle != null && bundle.getExplanations() != null) {
            for (String explanation : bundle.getExplanations()) {
                evidence.add(item("decision", explanation, 0.40, decision.getOverallConfidence(), "Decision Intelligence", timestamp));
            }
        }
        if (decision.getAttribution() != null && decision.getAttribution().getSources() != null) {
            for (PollutionSourceContribution source : decision.getAttribution().getSources()) {
                String sourceName = source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN";
                evidence.add(item("attribution", sourceName + " contribution " + source.getContributionPercent() + "%", source.getContributionPercent() / 100.0,
                        source.getConfidence(), "Attribution Engine", timestamp));
                if (source.getEvidence() != null) {
                    for (AttributionEvidence attrEvidence : source.getEvidence()) {
                        evidence.add(item(value(attrEvidence.getDataset(), "attribution"), value(attrEvidence.getSignal(), sourceName),
                                attrEvidence.getWeight(), source.getConfidence(), "Attribution Engine", timestamp));
                    }
                }
            }
        }
        if (decision.getForecast() != null && decision.getForecast().getForecast() != null) {
            decision.getForecast().getForecast().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> addForecastEvidence(evidence, entry.getKey(), entry.getValue(), timestamp));
        }
        if (decision.getEnforcement() != null && decision.getEnforcement().getRecommendations() != null) {
            for (EnforcementRecommendation recommendation : decision.getEnforcement().getRecommendations()) {
                String action = recommendation.getActionType() != null ? recommendation.getActionType().name() : "ENFORCEMENT";
                evidence.add(item("enforcement", value(recommendation.getReason(), action), recommendation.getPriorityScore() / 100.0,
                        recommendation.getConfidence(), value(recommendation.getResponsibleAgency(), "Enforcement Engine"), timestamp));
                if (recommendation.getEvidence() != null) {
                    for (EnforcementEvidence recEvidence : recommendation.getEvidence()) {
                        evidence.add(item(value(recEvidence.getDataset(), "enforcement"), value(recEvidence.getSignal(), recEvidence.getDescription()),
                                recEvidence.getConfidence(), recEvidence.getConfidence(), "Enforcement Engine", timestamp));
                    }
                }
            }
        }
        if (decision.getAdvisories() != null && decision.getAdvisories().getAdvisories() != null) {
            for (HealthAdvisory advisory : decision.getAdvisories().getAdvisories()) {
                evidence.add(item("advisory", value(advisory.getMessage(), advisory.getTitle()), severityWeight(advisory), advisory.getConfidence(),
                        "Citizen Advisory Engine", timestamp));
                if (advisory.getEvidence() != null) {
                    for (HealthAdvisoryEvidence advisoryEvidence : advisory.getEvidence()) {
                        evidence.add(item(value(advisoryEvidence.getDataset(), "advisory"), value(advisoryEvidence.getSignal(), advisoryEvidence.getDescription()),
                                advisoryEvidence.getConfidence(), advisoryEvidence.getConfidence(), "Citizen Advisory Engine", timestamp));
                    }
                }
            }
        }
        GeoSpatialSummary geospatial = decision.getGeospatialSummary();
        if (geospatial != null) {
            evidence.add(item("geospatial", "GeoSpatial layers available: " + geospatial.getLayerCount(), 0.45,
                    geospatial.getConfidence(), "GeoSpatial Intelligence Engine", timestamp));
        }
        return evidence.stream()
                .filter(item -> item.getSignal() != null && !item.getSignal().isBlank())
                .toList();
    }

    private void addForecastEvidence(List<EvidenceItem> evidence, String horizon, ForecastPoint point, Instant timestamp) {
        if (point == null) return;
        if (point.getPredictedAqi() == null) {
            evidence.add(item("forecast", horizon + " predicted AQI unavailable", 0.20, point.getConfidence(),
                    "Forecast Engine", timestamp));
            return;
        }
        evidence.add(item("forecast", horizon + " predicted AQI " + point.getPredictedAqi(), 0.65, point.getConfidence(),
                "Forecast Engine", timestamp));
        ForecastExplanation explanation = point.getExplanation();
        if (explanation != null) {
            if (explanation.getReasons() != null) {
                for (String reason : explanation.getReasons()) {
                    evidence.add(item("forecast", horizon + ": " + reason, 0.50, point.getConfidence(), "Forecast Engine", timestamp));
                }
            }
            if (explanation.getFallbackReason() != null && !explanation.getFallbackReason().isBlank()) {
                evidence.add(item("forecast", "Fallback reason: " + explanation.getFallbackReason(), 0.35, point.getConfidence(), "Forecast Engine", timestamp));
            }
        }
    }

    private List<ReasoningStep> reasoning(DecisionIntelligenceResult decision, ConfidenceBreakdown confidence) {
        List<ReasoningStep> steps = new ArrayList<>();
        int order = 1;
        if (decision.getCurrentAQI() != null && decision.getCurrentAQI() > 0) {
            steps.add(step(order++, "Current AQI", "Current AQI = " + decision.getCurrentAQI(), confidence.getAqi(), List.of("aqi")));
        }
        ForecastPoint peak = peakForecast(decision);
        if (peak != null && peak.getPredictedAqi() != null) {
            steps.add(step(order++, "Forecast", "Forecast predicts AQI " + peak.getPredictedAqi() + " over " + peak.getHorizonHours() + "h", confidence.getForecast(), List.of("forecast", "weather", "aqi")));
        }
        if (decision.getAttribution() != null && decision.getAttribution().getDominantSource() != null) {
            steps.add(step(order++, "Attribution", "Dominant pollution source = " + decision.getAttribution().getDominantSource(), confidence.getAttribution(), List.of("attribution", "aqi")));
            topSources(decision).forEach(source -> steps.add(step(steps.size() + 1, "Source Contribution",
                    source.getSourceType() + " contribution = " + source.getContributionPercent() + "%", source.getConfidence(), source.getDatasetsUsed())));
            order = steps.size() + 1;
        }
        if (decision.getForecast() != null && decision.getForecast().isFallbackUsed()) {
            steps.add(step(order++, "Forecast Fallback", "Forecast used fallback mode because model predictions were unavailable or degraded.", confidence.getForecast(), List.of("forecast")));
        }
        if (decision.getPriorityActions() != null) {
            for (PriorityAction action : decision.getPriorityActions().stream().limit(3).toList()) {
                steps.add(step(order++, "Recommendation", value(action.getActionType(), "Action") + ": " + value(action.getMessage(), "No message"), action.getConfidence(), List.of(action.getSourceEngine())));
            }
        }
        EngineStatus status = decision.getEngineStatus();
        if (status != null && status.isDegradedMode()) {
            steps.add(step(order, "Degraded Mode", "One or more engines/providers reported degraded or low-confidence output.", confidence.getOverall(), List.of("engineStatus")));
        }
        return steps;
    }

    private List<ModelExplanation> modelExplanations(DecisionIntelligenceResult decision, ConfidenceBreakdown confidence) {
        List<ModelExplanation> explanations = new ArrayList<>();
        explanations.add(ModelExplanation.builder()
                .modelName("Decision Intelligence")
                .outputType("Unified decision")
                .inputSignals(datasets(decision, List.of()))
                .rulesFired(rules(decision))
                .confidence(confidence.getOverall())
                .explanation(value(decision.getSummary() != null ? decision.getSummary().getWhatIsHappening() : null, "Decision summary unavailable."))
                .build());
        if (decision.getForecast() != null) {
            boolean forecastUnavailable = "UNAVAILABLE".equalsIgnoreCase(decision.getForecast().getMode())
                    || "UNAVAILABLE".equalsIgnoreCase(decision.getForecast().getModelVersion());
            explanations.add(ModelExplanation.builder()
                    .modelName("Forecast Engine")
                    .outputType("AQI forecast")
                    .inputSignals(List.of("current AQI", "historical AQI", "weather", "attribution"))
                    .rulesFired(forecastUnavailable ? List.of("forecast_unavailable_no_model_output")
                            : decision.getForecast().isFallbackUsed() ? List.of("forecast_fallback_used") : List.of("model_prediction_used"))
                    .confidence(confidence.getForecast())
                    .explanation(forecastUnavailable
                            ? "Forecast unavailable until a genuinely trained and evaluated model exists."
                            : "Forecast trend: " + value(decision.getForecast().getOverallTrend(), "unknown"))
                    .build());
        }
        if (decision.getAttribution() != null) {
            explanations.add(ModelExplanation.builder()
                    .modelName("Attribution Engine")
                    .outputType("Dominant source")
                    .inputSignals(List.of("aqi", "pollutants", "weather", "geospatial proxies", "fire evidence", "satellite evidence"))
                    .rulesFired(topSources(decision).stream().map(source -> "source_score_" + source.getSourceType()).toList())
                    .confidence(confidence.getAttribution())
                    .explanation(value(decision.getAttribution().getExplanation(), "Attribution explanation unavailable."))
                    .build());
        }
        return explanations;
    }

    private List<String> rules(DecisionIntelligenceResult decision) {
        List<String> rules = new ArrayList<>();
        if (decision.getCurrentAQI() != null && decision.getCurrentAQI() >= 300) rules.add("severe_aqi_alert");
        if (decision.getForecast() != null && ("UNAVAILABLE".equalsIgnoreCase(decision.getForecast().getMode())
                || "UNAVAILABLE".equalsIgnoreCase(decision.getForecast().getModelVersion()))) {
            rules.add("forecast_unavailable");
        } else if (decision.getForecast() != null && decision.getForecast().isFallbackUsed()) {
            rules.add("forecast_fallback");
        }
        if (decision.getAttribution() != null && decision.getAttribution().getDominantSource() != null) rules.add("dominant_source_" + decision.getAttribution().getDominantSource());
        if (decision.getEngineStatus() != null && decision.getEngineStatus().isDegradedMode()) rules.add("degraded_mode");
        if (rules.isEmpty()) rules.add("standard_decision_synthesis");
        return rules;
    }

    private List<String> datasets(DecisionIntelligenceResult decision, List<EvidenceItem> evidence) {
        Set<String> datasets = new LinkedHashSet<>();
        EvidenceBundle bundle = decision.getEvidenceBundle();
        if (bundle != null && bundle.getDatasetsUsed() != null) datasets.addAll(bundle.getDatasetsUsed());
        if (evidence != null) {
            evidence.stream().map(EvidenceItem::getDataset).filter(value -> value != null && !value.isBlank()).forEach(datasets::add);
        }
        if (decision.getGeospatialSummary() != null) datasets.add("geospatial");
        return new ArrayList<>(datasets);
    }

    private Map<String, String> providerStatus(DecisionIntelligenceResult decision) {
        EvidenceBundle bundle = decision.getEvidenceBundle();
        return bundle != null && bundle.getProviderStatus() != null ? new LinkedHashMap<>(bundle.getProviderStatus()) : new LinkedHashMap<>();
    }

    private List<String> limitations(DecisionIntelligenceResult decision, ConfidenceBreakdown confidence, Map<String, String> providerStatus, List<EvidenceItem> evidence) {
        List<String> limitations = new ArrayList<>();
        if (!providerStatus.containsKey("weather")) limitations.add("Weather provider status is unavailable in the decision evidence bundle.");
        if (!providerStatus.containsKey("satellite")) limitations.add("Satellite provider status is unavailable or satellite evidence was not included.");
        providerStatus.forEach((provider, status) -> {
            String normalized = status != null ? status.toUpperCase() : "";
            if (normalized.contains("FAIL") || normalized.contains("PARTIAL") || normalized.contains("DEGRADED")) {
                limitations.add(provider + " provider status = " + status + ".");
            }
        });
        if (decision.getForecast() != null && ("UNAVAILABLE".equalsIgnoreCase(decision.getForecast().getMode())
                || "UNAVAILABLE".equalsIgnoreCase(decision.getForecast().getModelVersion()))) {
            limitations.add("Forecast is unavailable until a genuinely trained and evaluated model exists.");
        } else if (decision.getForecast() != null && decision.getForecast().isFallbackUsed()) {
            limitations.add("Forecast engine used fallback mode, so forecast confidence is reduced.");
        }
        if (decision.getEngineStatus() != null && decision.getEngineStatus().isDegradedMode()) {
            limitations.add("Decision response is in degraded mode.");
            if (decision.getEngineStatus().getFailureReasons() != null) {
                decision.getEngineStatus().getFailureReasons().forEach((engine, reason) -> limitations.add(engine + " failure: " + reason));
            }
        }
        if (confidence.getOverall() < 0.45) limitations.add("Overall confidence is low; recommendations should be reviewed before action.");
        if (evidence.isEmpty()) limitations.add("No detailed evidence items were available from engine outputs.");
        return limitations.stream().distinct().toList();
    }

    private String narrative(DecisionIntelligenceResult decision, List<ReasoningStep> reasoning, List<String> limitations) {
        List<String> parts = new ArrayList<>();
        if (decision.getSummary() != null) {
            parts.add(value(decision.getSummary().getWhatIsHappening(), ""));
            parts.add(value(decision.getSummary().getWhyIsItHappening(), ""));
            parts.add(value(decision.getSummary().getWhatWillHappenNext(), ""));
        }
        if (decision.getAttribution() != null && decision.getAttribution().getDominantSource() != null) {
            parts.add("The dominant source identified by attribution is " + decision.getAttribution().getDominantSource() + ".");
        }
        ForecastPoint peak = peakForecast(decision);
        if (peak != null && peak.getPredictedAqi() != null) {
            parts.add("The forecast peak is " + peak.getPredictedAqi() + " AQI at the " + peak.getHorizonHours() + " hour horizon.");
        }
        if (decision.getPriorityActions() != null && !decision.getPriorityActions().isEmpty()) {
            parts.add("Recommended actions include " + decision.getPriorityActions().stream()
                    .limit(3)
                    .map(action -> value(action.getActionType(), "ACTION"))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("targeted response") + ".");
        }
        if (!limitations.isEmpty()) {
            parts.add("Limitations: " + limitations.get(0));
        }
        String narrative = String.join(" ", parts.stream().filter(value -> value != null && !value.isBlank()).toList());
        return narrative.isBlank() ? "Explainability is limited because the decision response did not include enough engine output." : narrative;
    }

    private double explainabilityScore(List<ReasoningStep> reasoning, List<EvidenceItem> evidence, List<String> datasets,
                                       List<String> limitations, DecisionIntelligenceResult decision) {
        double score = 0.20;
        score += Math.min(0.25, reasoning.size() * 0.04);
        score += Math.min(0.25, evidence.size() * 0.02);
        score += Math.min(0.15, datasets.size() * 0.02);
        if (decision.getEvidenceBundle() != null && decision.getEvidenceBundle().getProviderStatus() != null && !decision.getEvidenceBundle().getProviderStatus().isEmpty()) score += 0.10;
        score -= Math.min(0.25, limitations.size() * 0.04);
        return round(clamp(score, 0.05, 0.98));
    }

    private ForecastPoint peakForecast(DecisionIntelligenceResult decision) {
        return decision.getForecast() != null && decision.getForecast().getForecast() != null
                ? decision.getForecast().getForecast().values().stream()
                .filter(point -> point != null && point.getPredictedAqi() != null && point.getPredictedAqi() > 0)
                .max(Comparator.comparingInt(ForecastPoint::getPredictedAqi))
                .orElse(null)
                : null;
    }

    private List<PollutionSourceContribution> topSources(DecisionIntelligenceResult decision) {
        return decision.getAttribution() != null && decision.getAttribution().getSources() != null
                ? decision.getAttribution().getSources().stream()
                .sorted(Comparator.comparingInt(PollutionSourceContribution::getContributionPercent).reversed())
                .limit(3)
                .toList()
                : List.of();
    }

    private ReasoningStep step(int order, String stage, String statement, double confidence, List<String> datasets) {
        return ReasoningStep.builder()
                .order(order)
                .stage(stage)
                .statement(statement)
                .confidence(round(confidence))
                .datasetsUsed(datasets != null ? datasets : List.of())
                .build();
    }

    private EvidenceItem item(String dataset, String signal, double weight, double confidence, String provider, Instant timestamp) {
        return EvidenceItem.builder()
                .dataset(value(dataset, "unknown"))
                .signal(value(signal, ""))
                .weight(round(clamp(weight, 0.0, 1.0)))
                .confidence(round(clamp(confidence, 0.0, 1.0)))
                .provider(value(provider, "unknown"))
                .timestamp(timestamp)
                .build();
    }

    private double confidenceFromRecommendations(DecisionIntelligenceResult decision) {
        return decision.getEnforcement() != null && decision.getEnforcement().getRecommendations() != null
                ? decision.getEnforcement().getRecommendations().stream().mapToDouble(EnforcementRecommendation::getConfidence).average().orElse(0.0)
                : 0.0;
    }

    private double confidenceFromAdvisories(DecisionIntelligenceResult decision) {
        return decision.getAdvisories() != null && decision.getAdvisories().getAdvisories() != null
                ? decision.getAdvisories().getAdvisories().stream().mapToDouble(HealthAdvisory::getConfidence).average().orElse(0.0)
                : 0.0;
    }

    private double confidenceFromProvider(String status) {
        if (status == null || status.isBlank()) return 0.20;
        String normalized = status.toUpperCase();
        if (normalized.contains("SUCCESS")) return 0.80;
        if (normalized.contains("PARTIAL") || normalized.contains("DEGRADED")) return 0.40;
        if (normalized.contains("FAIL")) return 0.10;
        return 0.30;
    }

    private double severityWeight(HealthAdvisory advisory) {
        return advisory != null && advisory.getSeverity() != null ? (advisory.getSeverity().ordinal() + 1) / 5.0 : 0.30;
    }

    private String decisionId(DecisionIntelligenceResult decision) {
        String cityId = value(decision.getCityId(), "UNKNOWN");
        String generatedAt = decision.getGeneratedAt() != null ? String.valueOf(decision.getGeneratedAt().toEpochMilli()) : String.valueOf(Instant.now().toEpochMilli());
        return cityId + "-" + generatedAt;
    }

    private double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double firstPositive(double... values) {
        for (double value : values) {
            if (value > 0) return value;
        }
        return 0.0;
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private String value(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
