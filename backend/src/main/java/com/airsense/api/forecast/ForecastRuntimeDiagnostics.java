package com.airsense.api.forecast;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ForecastRuntimeDiagnostics {
    private final Environment environment;
    private final MlForecastProperties mlForecastProperties;
    private final ForecastOrchestrator forecastOrchestrator;
    private final MlForecastClient mlForecastClient;

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupConfiguration() {
        log.info("Forecast runtime config profiles={} aiServiceHost={} mlForecastServiceUrl={}",
                String.join(",", environment.getActiveProfiles()),
                host(environment.getProperty("ai.service.base-url", "")),
                normalize(mlForecastProperties.getServiceUrl()));
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeProfiles", environment.getActiveProfiles());
        data.put("aiServiceHost", host(environment.getProperty("ai.service.base-url", "")));
        data.put("mlForecastServiceUrl", normalize(mlForecastProperties.getServiceUrl()));
        data.put("mlForecastEnabled", mlForecastProperties.isEnabled());
        data.put("mlForecastConfiguredTimeoutSeconds", mlForecastProperties.getTimeoutSeconds());
        data.put("mlForecastEffectiveTimeoutSeconds", mlForecastClient.effectiveTimeoutSeconds());
        data.put("providerForecastStandard", mlForecastProperties.getProviderForecastStandard());
        data.put("providerForecastProvider", mlForecastProperties.getProviderForecastProvider());
        data.put("lastAiRequest", forecastOrchestrator.lastForecastTrace());
        data.put("lastAiResponse", mlForecastClient.lastTrace());
        return data;
    }

    private String normalize(String url) {
        if (url == null) return "";
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String host(String url) {
        try {
            URI uri = URI.create(normalize(url));
            return uri.getHost() != null ? uri.getHost() : normalize(url);
        } catch (Exception ignored) {
            return normalize(url);
        }
    }
}
