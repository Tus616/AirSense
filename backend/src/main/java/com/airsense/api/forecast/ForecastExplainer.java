package com.airsense.api.forecast;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ForecastExplainer {
    public ForecastExplanation explain(ForecastFeatures features, ForecastPoint point, boolean fallbackUsed, String fallbackReason) {
        if (point == null || point.getPredictedAqi() == null || "UNAVAILABLE".equalsIgnoreCase(point.getMode())) {
            return ForecastExplanation.builder()
                    .summary("Forecast unavailable until a genuinely trained and evaluated model exists; no baseline value was emitted.")
                    .reasons(List.of(
                            "predicted AQI = null",
                            "forecast mode = UNAVAILABLE",
                            "baseline, random and hardcoded predictions disabled"
                    ))
                    .dataUsed(features.getAvailableDatasets())
                    .fallbackReason(fallbackReason)
                    .build();
        }
        return ForecastExplanation.builder()
                .summary("Forecast unavailable.")
                .reasons(List.of("No trained model output is enabled."))
                .dataUsed(features.getAvailableDatasets())
                .fallbackReason(fallbackReason)
                .build();
    }

    public String meteorologicalInfluence(ForecastFeatures features) {
        if (features.getRainProbability() >= 0.50 || features.getRainfall() > 0.5) {
            return "WASHOUT";
        }
        if (features.getWindSpeed() > 0 && features.getWindSpeed() <= 2.0) {
            return "ACCUMULATION";
        }
        if (features.getWindSpeed() >= 6.0) {
            return "DISPERSION";
        }
        if (features.getHumidity() >= 75) {
            return "HAZE_SUPPORT";
        }
        return "NEUTRAL";
    }

}
