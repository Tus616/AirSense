package com.airsense.api.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiClientTest {
    private final GeminiClient client = new GeminiClient(1000, new RestTemplateBuilder(), new ObjectMapper(), new SystemMetricsService());

    @Test
    void classifiesRequestsPerMinuteQuota() {
        GeminiClient.GeminiServiceException ex = client.classifyHttpExceptionForTesting(quota429("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "requests per minute exceeded"));

        assertThat(ex.getCategory()).isEqualTo("REQUESTS_PER_MINUTE_EXCEEDED");
        assertThat(ex.getSanitizedMessage()).contains("requests-per-minute quota");
        assertThat(ex.getSanitizedMessage()).doesNotContain("AIza");
    }

    @Test
    void classifiesTokensPerMinuteQuota() {
        GeminiClient.GeminiServiceException ex = client.classifyHttpExceptionForTesting(quota429("GenerateContentInputTokensPerMinutePerProjectPerModel-FreeTier", "tokens per minute exceeded"));

        assertThat(ex.getCategory()).isEqualTo("TOKENS_PER_MINUTE_EXCEEDED");
    }

    @Test
    void classifiesDailyQuotaWithoutRetryCategory() {
        GeminiClient.GeminiServiceException ex = client.classifyHttpExceptionForTesting(quota429("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "daily quota exceeded"));

        assertThat(ex.getCategory()).isEqualTo("DAILY_QUOTA_EXCEEDED");
        assertThat(ex.getSanitizedMessage()).contains("daily quota");
    }

    @Test
    void classifiesProjectQuotaUnavailable() {
        GeminiClient.GeminiServiceException ex = client.classifyHttpExceptionForTesting(quota429("ConsumerQuota", "project quota unavailable for this consumer"));

        assertThat(ex.getCategory()).isEqualTo("PROJECT_QUOTA_UNAVAILABLE");
    }

    @Test
    void classifiesSpendingLimitBeforeGenericProjectQuota() {
        GeminiClient.GeminiServiceException ex = client.classifyHttpExceptionForTesting(quota429("ConsumerQuota", "project billing spending limit exceeded"));

        assertThat(ex.getCategory()).isEqualTo("SPENDING_LIMIT_EXCEEDED");
    }

    @Test
    void retryDelayUsesRetryAfterHeaderWhenPresent() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "2");
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                headers,
                "{}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(client.retryDelayMsForTesting(ex, 1)).isBetween(2000L, 2450L);
    }

    @Test
    void retryDelayUsesProviderRetryDelayWhenPresent() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                """
                        {
                          "error": {
                            "details": [
                              {
                                "@type": "type.googleapis.com/google.rpc.RetryInfo",
                                "retryDelay": "3.5s"
                              }
                            ]
                          }
                        }
                        """.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(client.retryDelayMsForTesting(ex, 1)).isBetween(3500L, 3950L);
    }

    private HttpClientErrorException quota429(String quotaId, String description) {
        String body = """
                {
                  "error": {
                    "code": 429,
                    "message": "Quota exceeded.",
                    "status": "RESOURCE_EXHAUSTED",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                        "violations": [
                          {
                            "quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                            "quotaId": "%s",
                            "quotaDimensions": {"model": "gemini-2.5-flash-lite", "location": "global"},
                            "description": "%s"
                          }
                        ]
                      }
                    ]
                  }
                }
                """.formatted(quotaId, description);
        return HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
