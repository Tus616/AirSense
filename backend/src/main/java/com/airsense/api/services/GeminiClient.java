package com.airsense.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeminiClient {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.model:gemini-2.5-flash}")
    private String model;

    @Value("${gemini.api.max-retries:2}")
    private int maxRetries;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SystemMetricsService metricsService;

    public GeminiClient(@Value("${gemini.api.timeout-ms:30000}") long timeoutMs, RestTemplateBuilder builder, ObjectMapper objectMapper, SystemMetricsService metricsService) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(timeoutMs))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @PostConstruct
    public void init() {
        if (isConfigured()) {
            log.info("GeminiClient initialized in LIVE mode with model={}.", model);
        } else {
            log.info("GeminiClient initialized in FALLBACK mode (no API key) with model={}.", model);
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public JsonNode generateContent(String prompt, boolean expectJson) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> content = new HashMap<>();
        content.put("parts", List.of(Map.of("text", prompt)));
        requestBody.put("contents", List.of(content));

        if (expectJson) {
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("temperature", 0.7);
            requestBody.put("generationConfig", generationConfig);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = exchangeWithRetry(url, entity);
        
        JsonNode rootNode = objectMapper.readTree(response.getBody());
        JsonNode candidates = rootNode.path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
            JsonNode contentNode = candidates.get(0).path("content");
            JsonNode parts = contentNode.path("parts");
            if (parts.isArray() && parts.size() > 0) {
                String text = parts.get(0).path("text").asText();
                if (expectJson) {
                    return objectMapper.readTree(text);
                } else {
                    return objectMapper.createObjectNode().put("text", text);
                }
            }
        }
        throw GeminiServiceException.malformed("Malformed Gemini response.", 0);
    }

    public AdvisoryResult generateAdvisory(String prompt) {
        if (!isConfigured()) {
            log.warn("Gemini API key is not configured. Skipping advisory generation.");
            return AdvisoryResult.skipped("API key not configured");
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        // Construct Gemini JSON payload with strict JSON formatting instruction
        Map<String, Object> requestBody = new HashMap<>();
        
        Map<String, Object> content = new HashMap<>();
        content.put("parts", List.of(Map.of("text", prompt)));
        
        requestBody.put("contents", List.of(content));

        // Use generationConfig to enforce JSON response
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.7);
        requestBody.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response;
        try {
            response = exchangeWithRetry(url, entity);
        } catch (GeminiServiceException e) {
            log.warn("Failed to generate advisory via Gemini API: category={} retries={}", e.getCategory(), e.getRetriesAttempted());
            return AdvisoryResult.failed(e.getSanitizedMessage());
        } catch (Exception e) {
            log.warn("Failed to generate advisory via Gemini API: provider failure");
            return AdvisoryResult.failed("Gemini unavailable: provider failure.");
        }

        try {
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            JsonNode candidates = rootNode.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode contentNode = candidates.get(0).path("content");
                JsonNode parts = contentNode.path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    String jsonText = parts.get(0).path("text").asText();
                    return parseAdvisoryJson(jsonText);
                }
            }
            return AdvisoryResult.failed("Unexpected response structure from Gemini API.");
            
        } catch (Exception e) {
            log.error("Failed to parse Gemini API response: {}", e.getMessage());
            return AdvisoryResult.failed(e.getMessage());
        }
    }

    private ResponseEntity<String> exchangeWithRetry(String url, HttpEntity<Map<String, Object>> entity) throws Exception {
        long startTime = System.currentTimeMillis();
        int retriesAttempted = 0;
        int retriesAllowed = Math.max(0, Math.min(2, maxRetries));
        List<GeminiQuotaDetail> quotaDetails = List.of();

        while (true) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                metricsService.recordGeminiCall(System.currentTimeMillis() - startTime, true);
                return response;
            } catch (HttpStatusCodeException ex) {
                GeminiFailure failure = classifyHttpFailure(ex, retriesAttempted);
                quotaDetails = failure.quotaDetails();
                if (!failure.retryable() || retriesAttempted >= retriesAllowed) {
                    metricsService.recordGeminiCall(System.currentTimeMillis() - startTime, false);
                    throw failure.toException(retriesAttempted);
                }
                retriesAttempted++;
                long delayMs = retryDelayMs(ex, retriesAttempted);
                log.warn("Gemini API transient failure category={} retry={}/{} waitMs={}",
                        failure.category(), retriesAttempted, retriesAllowed, delayMs);
                sleep(delayMs);
            } catch (ResourceAccessException ex) {
                if (retriesAttempted >= retriesAllowed) {
                    metricsService.recordGeminiCall(System.currentTimeMillis() - startTime, false);
                    throw GeminiServiceException.network("Gemini unavailable: network failure.", retriesAttempted);
                }
                retriesAttempted++;
                long delayMs = backoffMs(retriesAttempted);
                log.warn("Gemini API network failure retry={}/{} waitMs={}", retriesAttempted, retriesAllowed, delayMs);
                sleep(delayMs);
            } catch (Exception ex) {
                metricsService.recordGeminiCall(System.currentTimeMillis() - startTime, false);
                throw ex;
            }
        }
    }

    private GeminiFailure classifyHttpFailure(HttpStatusCodeException ex, int retriesAttempted) {
        int status = ex.getStatusCode().value();
        if (status == 429) {
            List<GeminiQuotaDetail> details = quotaDetails(ex.getResponseBodyAsString());
            String category = quotaCategory(details, ex.getResponseBodyAsString());
            boolean retryable = category.equals("REQUESTS_PER_MINUTE_EXCEEDED")
                    || category.equals("TOKENS_PER_MINUTE_EXCEEDED")
                    || category.equals("RATE_LIMIT_EXCEEDED");
            return new GeminiFailure(status, category, retryable,
                    sanitizedQuotaMessage(category), details);
        }
        if (status >= 500 && status <= 599) {
            return new GeminiFailure(status, "PROVIDER_5XX", true,
                    "Gemini is temporarily unavailable; showing a grounded platform fallback.", List.of());
        }
        if (status == 401 || status == 403) {
            return new GeminiFailure(status, "API_KEY_REJECTED", false,
                    "Gemini API key was rejected; showing a grounded platform fallback.", List.of());
        }
        if (status == 400 || status == 404) {
            return new GeminiFailure(status, "MODEL_OR_REQUEST_REJECTED", false,
                    "Gemini model or request was rejected; showing a grounded platform fallback.", List.of());
        }
        return new GeminiFailure(status, "PROVIDER_HTTP_" + status, false,
                "Gemini is unavailable; showing a grounded platform fallback.", List.of());
    }

    GeminiServiceException classifyHttpExceptionForTesting(HttpStatusCodeException ex) {
        return classifyHttpFailure(ex, 0).toException(0);
    }

    long retryDelayMsForTesting(HttpStatusCodeException ex, int retryNumber) {
        return retryDelayMs(ex, retryNumber);
    }

    private List<GeminiQuotaDetail> quotaDetails(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<GeminiQuotaDetail> details = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode detailNodes = root.path("error").path("details");
            if (!detailNodes.isArray()) return List.of();
            for (JsonNode detail : detailNodes) {
                if (!detail.path("@type").asText("").contains("QuotaFailure")) continue;
                JsonNode violations = detail.path("violations");
                if (!violations.isArray()) continue;
                for (JsonNode violation : violations) {
                    details.add(new GeminiQuotaDetail(
                            safeQuotaText(violation.path("quotaMetric").asText("")),
                            safeQuotaText(violation.path("quotaId").asText("")),
                            safeQuotaText(violation.path("quotaDimensions").toString()),
                            safeQuotaText(violation.path("description").asText(""))));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return details;
    }

    private String quotaCategory(List<GeminiQuotaDetail> details, String body) {
        for (GeminiQuotaDetail detail : details) {
            String specific = (detail.quotaId() + " " + detail.description()).toLowerCase();
            String category = quotaCategoryFromText(specific);
            if (category != null) return category;
        }
        String text = (details.toString() + " " + (body == null ? "" : body)).toLowerCase();
        String category = quotaCategoryFromText(text);
        return category != null ? category : "RATE_LIMIT_EXCEEDED";
    }

    private String quotaCategoryFromText(String text) {
        if (containsAny(text, "free_tier_requests", "requestsperminute", "requests per minute", "request per minute", "rpm")) {
            return "REQUESTS_PER_MINUTE_EXCEEDED";
        }
        if (containsAny(text, "tokensperminute", "tokens per minute", "token per minute", "tpm", "input tokens", "output tokens")) {
            return "TOKENS_PER_MINUTE_EXCEEDED";
        }
        if (containsAny(text, "perday", "per day", "daily", "requestsperday", "rpd")) {
            return "DAILY_QUOTA_EXCEEDED";
        }
        if (containsAny(text, "billing", "spending", "budget", "payment")) {
            return "SPENDING_LIMIT_EXCEEDED";
        }
        if (containsAny(text, "project", "consumer", "quota unavailable", "not available", "has not been used")) {
            return "PROJECT_QUOTA_UNAVAILABLE";
        }
        return null;
    }

    private String sanitizedQuotaMessage(String category) {
        return switch (category) {
            case "REQUESTS_PER_MINUTE_EXCEEDED" -> "Gemini requests-per-minute quota is currently exhausted; showing a grounded platform fallback.";
            case "TOKENS_PER_MINUTE_EXCEEDED" -> "Gemini tokens-per-minute quota is currently exhausted; showing a grounded platform fallback.";
            case "DAILY_QUOTA_EXCEEDED" -> "Gemini daily quota is currently exhausted; showing a grounded platform fallback.";
            case "PROJECT_QUOTA_UNAVAILABLE" -> "Gemini project quota is currently unavailable; showing a grounded platform fallback.";
            case "SPENDING_LIMIT_EXCEEDED" -> "Gemini spending limit is currently exhausted; showing a grounded platform fallback.";
            default -> "Gemini quota is currently unavailable; showing a grounded platform fallback.";
        };
    }

    private long retryDelayMs(HttpStatusCodeException ex, int retryNumber) {
        String retryAfter = ex.getResponseHeaders() != null ? ex.getResponseHeaders().getFirst("Retry-After") : null;
        Long retryAfterMs = parseRetryAfterMs(retryAfter);
        if (retryAfterMs != null) return withJitter(retryAfterMs);
        Long retryDelayMs = parseRetryDelayMs(ex.getResponseBodyAsString());
        if (retryDelayMs != null) return withJitter(retryDelayMs);
        return backoffMs(retryNumber);
    }

    private Long parseRetryAfterMs(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.max(0, Math.min(seconds * 1000L, 30000L));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long parseRetryDelayMs(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode details = root.path("error").path("details");
            if (!details.isArray()) return null;
            for (JsonNode detail : details) {
                String retryDelay = detail.path("retryDelay").asText("");
                if (retryDelay.isBlank()) continue;
                Matcher matcher = Pattern.compile("^(\\d+(?:\\.\\d+)?)s$").matcher(retryDelay.trim());
                if (matcher.matches()) {
                    double seconds = Double.parseDouble(matcher.group(1));
                    return Math.max(0, Math.min((long) (seconds * 1000L), 30000L));
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private long backoffMs(int retryNumber) {
        long base = (long) (750L * Math.pow(2, Math.max(0, retryNumber - 1)));
        return withJitter(Math.min(base, 5000L));
    }

    private long withJitter(long baseMs) {
        long jitter = ThreadLocalRandom.current().nextLong(100L, 451L);
        return Math.min(baseMs + jitter, 30000L);
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeQuotaText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private record GeminiFailure(int status, String category, boolean retryable, String sanitizedMessage,
                                 List<GeminiQuotaDetail> quotaDetails) {
        private GeminiServiceException toException(int retriesAttempted) {
            return new GeminiServiceException(category, sanitizedMessage, retriesAttempted, status, quotaDetails);
        }
    }

    private record GeminiQuotaDetail(String quotaMetric, String quotaId, String quotaDimensions, String description) {
    }

    public static class GeminiServiceException extends RuntimeException {
        private final String category;
        private final String sanitizedMessage;
        private final int retriesAttempted;
        private final int httpStatus;
        private final List<?> quotaDetails;

        private GeminiServiceException(String category, String sanitizedMessage, int retriesAttempted, int httpStatus, List<?> quotaDetails) {
            super(sanitizedMessage);
            this.category = category;
            this.sanitizedMessage = sanitizedMessage;
            this.retriesAttempted = retriesAttempted;
            this.httpStatus = httpStatus;
            this.quotaDetails = quotaDetails == null ? List.of() : List.copyOf(quotaDetails);
        }

        public static GeminiServiceException malformed(String message, int retriesAttempted) {
            return new GeminiServiceException("MALFORMED_RESPONSE", message, retriesAttempted, 0, List.of());
        }

        public static GeminiServiceException network(String message, int retriesAttempted) {
            return new GeminiServiceException("NETWORK_FAILURE", message, retriesAttempted, 0, List.of());
        }

        public String getCategory() {
            return category;
        }

        public String getSanitizedMessage() {
            return sanitizedMessage;
        }

        public int getRetriesAttempted() {
            return retriesAttempted;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public List<?> getQuotaDetails() {
            return quotaDetails;
        }
    }

    private AdvisoryResult parseAdvisoryJson(String jsonText) {
        try {
            JsonNode resultNode = objectMapper.readTree(jsonText);
            String municipalDirective = resultNode.path("municipalDirective").asText("");
            String citizenAdvisory = resultNode.path("citizenAdvisory").asText("");

            if (municipalDirective.isEmpty() || citizenAdvisory.isEmpty()) {
                return AdvisoryResult.failed("Gemini response missing required JSON fields.");
            }
            
            return AdvisoryResult.success(municipalDirective, citizenAdvisory);
        } catch (Exception e) {
            log.error("Failed to parse JSON from Gemini response.");
            return AdvisoryResult.failed("JSON parse error: " + e.getMessage());
        }
    }

    public String translateAdvisory(String text, String targetLanguageCode) {
        if (!isConfigured() || text == null || text.trim().isEmpty()) {
            return text; // fallback to original
        }
        
        String prompt = "Translate the following public health advisory into " + (targetLanguageCode.equalsIgnoreCase("hi") ? "Hindi" : targetLanguageCode) + 
            ". IMPORTANT: Do NOT add any extra information, do NOT hallucinate facts, and maintain the exact original meaning and tone.\n\nText: " + text;
        
        try {
            JsonNode response = generateContent(prompt, false);
            if (response.has("text")) {
                return response.get("text").asText();
            }
            return text;
        } catch (Exception e) {
            log.error("Failed to translate advisory: {}", e.getMessage());
            return text; // fallback
        }
    }

    public static class AdvisoryResult {
        public final String status; // SUCCESS, SKIPPED, FAILED
        public final String municipalDirective;
        public final String citizenAdvisory;
        public final String errorMessage;

        private AdvisoryResult(String status, String municipalDirective, String citizenAdvisory, String errorMessage) {
            this.status = status;
            this.municipalDirective = municipalDirective;
            this.citizenAdvisory = citizenAdvisory;
            this.errorMessage = errorMessage;
        }

        public static AdvisoryResult success(String municipalDirective, String citizenAdvisory) {
            return new AdvisoryResult("SUCCESS", municipalDirective, citizenAdvisory, null);
        }

        public static AdvisoryResult skipped(String reason) {
            return new AdvisoryResult("SKIPPED", null, null, reason);
        }

        public static AdvisoryResult failed(String error) {
            return new AdvisoryResult("FAILED", null, null, error);
        }
    }
}
