package com.airsense.api.advisory;

import com.airsense.api.attribution.PollutionSourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AdvisoryMessageCatalog {
    public String title(AdvisoryTargetGroup targetGroup, AdvisorySeverity severity, boolean improving) {
        String group = groupLabel(targetGroup);
        if (severity == AdvisorySeverity.LOW) {
            return group + ": air quality is acceptable";
        }
        if (improving) {
            return group + ": pollution risk is easing, precautions still advised";
        }
        return group + ": " + severityLabel(severity) + " air quality precautions";
    }

    public String message(AdvisoryTargetGroup targetGroup, AdvisoryFacts facts) {
        if (!facts.dataAvailable()) {
            return "Environmental intelligence is incomplete for " + facts.city()
                    + ", so use precautionary exposure limits until fresh AQI and weather data are available.";
        }
        if (facts.forecastPeakAqi() == null) {
            return "AQI in " + facts.city() + " is " + facts.currentAqi()
                    + ". Forecast is unavailable, so advice is based on the current CPCB station AQI, weather and attribution evidence. "
                    + reasonSentence(facts) + " Advice is tailored for " + groupLabel(targetGroup) + ".";
        }
        String movement = facts.improving() ? "is expected to improve" : facts.worsening() ? "is expected to worsen" : "is expected to remain stable";
        return "AQI in " + facts.city() + " is " + facts.currentAqi()
                + " and " + movement + " toward " + facts.forecastPeakAqi()
                + ". " + reasonSentence(facts) + " Advice is tailored for " + groupLabel(targetGroup) + ".";
    }

    public List<String> actions(AdvisoryTargetGroup targetGroup, AdvisoryFacts facts) {
        List<String> actions = new ArrayList<>();
        if (!facts.dataAvailable()) {
            actions.add("Check the latest local AQI before planning outdoor activity");
            actions.add("Use shorter outdoor exposure windows until updated data is available");
            return actions;
        }
        if (facts.severity() == AdvisorySeverity.LOW) {
            actions.add("Continue normal activity and keep monitoring AQI updates");
            actions.add("Ventilate homes during periods of lower traffic and cleaner air");
        } else {
            actions.add("Track AQI updates for the next 24 hours");
            actions.add("Prefer indoor or low-exposure routes during peak pollution periods");
        }
        switch (targetGroup) {
            case CHILDREN -> {
                actions.add("Move sports and play to indoor spaces when AQI rises");
                actions.add("Ensure children with cough or wheeze are monitored closely");
            }
            case ELDERLY -> {
                actions.add("Keep medication accessible and reduce outdoor errands");
                actions.add("Use indoor air filtration where available");
            }
            case PREGNANT_WOMEN -> {
                actions.add("Avoid prolonged roadside exposure");
                actions.add("Plan essential travel during lower-pollution hours");
            }
            case ASTHMA_COPD_PATIENTS -> {
                actions.add("Keep rescue inhaler and prescribed medication available");
                actions.add("Use an N95 mask if outdoor exposure is unavoidable");
            }
            case OUTDOOR_WORKERS -> {
                actions.add("Use N95 masks during dusty or high-traffic work");
                actions.add("Schedule rest breaks away from traffic and construction dust");
            }
            case CYCLISTS_RUNNERS -> {
                actions.add("Shift running or cycling indoors or to lower AQI windows");
                actions.add("Use routes away from congested corridors");
            }
            case SCHOOLS -> {
                actions.add("Review outdoor assembly and sports plans against the forecast");
                actions.add("Keep vulnerable students indoors if AQI worsens");
            }
            case HOSPITALS -> {
                actions.add("Prepare triage for respiratory symptoms if AQI remains high");
                actions.add("Communicate precautions to respiratory and cardiac patients");
            }
            default -> actions.add("Reduce unnecessary outdoor exposure if symptoms appear");
        }
        if (facts.rainImproving()) {
            actions.add("Resume outdoor activity gradually after rainfall clears local pollution");
        }
        if (facts.heatRisk()) {
            actions.add("Combine pollution precautions with hydration and heat-stress breaks");
        }
        return actions;
    }

    public List<String> avoidActivities(AdvisoryTargetGroup targetGroup, AdvisoryFacts facts) {
        List<String> avoid = new ArrayList<>();
        if (!facts.dataAvailable()) {
            avoid.add("Avoid relying on stale AQI information for sensitive groups");
            return avoid;
        }
        if (facts.severity().ordinal() >= AdvisorySeverity.HIGH.ordinal()) {
            avoid.add("Avoid prolonged outdoor exertion near traffic or dusty roads");
        }
        if (facts.source() == PollutionSourceType.TRAFFIC) {
            avoid.add("Avoid peak-hour roadside exposure");
        }
        if (facts.source() == PollutionSourceType.INDUSTRIAL) {
            avoid.add("Avoid unnecessary time near industrial corridors");
        }
        if (facts.source() == PollutionSourceType.ROAD_DUST_CONSTRUCTION) {
            avoid.add("Avoid dusty construction stretches and unpaved road edges");
        }
        if (facts.weatherTrapping()) {
            avoid.add("Avoid early morning exposure when stagnant air can trap pollutants");
        }
        switch (targetGroup) {
            case CHILDREN, SCHOOLS -> avoid.add("Avoid outdoor sports and assemblies during the risk window");
            case ASTHMA_COPD_PATIENTS, ELDERLY, PREGNANT_WOMEN -> avoid.add("Avoid long walks or waiting outdoors in polluted corridors");
            case CYCLISTS_RUNNERS -> avoid.add("Avoid high-intensity training outdoors until AQI improves");
            case OUTDOOR_WORKERS -> avoid.add("Avoid continuous outdoor work without mask and rest breaks");
            default -> {
            }
        }
        if (avoid.isEmpty()) {
            avoid.add("No special activity restrictions beyond normal AQI monitoring");
        }
        return avoid;
    }

    public String evidenceDescription(String key, AdvisoryFacts facts) {
        Map<String, String> descriptions = Map.of(
                "aqi", "Current CPCB AQI defines the exposure risk window when forecast is unavailable",
                "forecast", "Forecast is unavailable until a genuinely trained and evaluated model exists",
                "attribution", "Dominant pollution source shapes source-specific exposure advice",
                "weather", "Wind, humidity, rain, and heat influence pollutant dispersion and health risk",
                "enforcement", "Government-action recommendations indicate affected activity sectors"
        );
        return descriptions.getOrDefault(key, "Environmental intelligence supports this advisory");
    }

    private String reasonSentence(AdvisoryFacts facts) {
        List<String> reasons = new ArrayList<>();
        if (facts.weatherTrapping()) reasons.add("low wind or high humidity may trap pollutants");
        if (facts.source() == PollutionSourceType.TRAFFIC) reasons.add("traffic is the likely dominant source");
        if (facts.source() == PollutionSourceType.INDUSTRIAL) reasons.add("industrial emissions are a likely contributor");
        if (facts.source() == PollutionSourceType.ROAD_DUST_CONSTRUCTION) reasons.add("road dust or construction evidence is a likely contributor");
        if (facts.rainImproving()) reasons.add("rainfall is expected to wash out some pollution");
        if (facts.heatRisk()) reasons.add("heat can increase outdoor stress");
        if (reasons.isEmpty()) reasons.add("available AQI and provider signals support this guidance");
        return String.join(", ", reasons) + ".";
    }

    private String groupLabel(AdvisoryTargetGroup targetGroup) {
        return switch (targetGroup) {
            case GENERAL_PUBLIC -> "General public";
            case CHILDREN -> "Children";
            case ELDERLY -> "Elderly";
            case PREGNANT_WOMEN -> "Pregnant women";
            case ASTHMA_COPD_PATIENTS -> "Asthma/COPD patients";
            case OUTDOOR_WORKERS -> "Outdoor workers";
            case CYCLISTS_RUNNERS -> "Cyclists and runners";
            case SCHOOLS -> "Schools";
            case HOSPITALS -> "Hospitals";
        };
    }

    private String severityLabel(AdvisorySeverity severity) {
        return severity.name().toLowerCase().replace('_', ' ');
    }

    public record AdvisoryFacts(
            String city,
            int currentAqi,
            Integer forecastPeakAqi,
            AdvisorySeverity severity,
            PollutionSourceType source,
            boolean worsening,
            boolean improving,
            boolean weatherTrapping,
            boolean rainImproving,
            boolean heatRisk,
            boolean dataAvailable
    ) {
    }
}
