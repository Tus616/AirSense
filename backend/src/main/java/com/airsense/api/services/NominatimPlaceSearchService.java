package com.airsense.api.services;

import com.airsense.api.dto.PlaceSearchResponseDto;
import com.airsense.api.dto.PlaceSearchResultDto;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class NominatimPlaceSearchService {
    private final RestTemplate restTemplate;

    @Value("${nominatim.base-url:https://nominatim.openstreetmap.org}")
    private String baseUrl;

    @Value("${nominatim.user-agent:ETAI-AirSense/1.0 (local-development)}")
    private String userAgent;

    public NominatimPlaceSearchService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(4))
                .setReadTimeout(Duration.ofSeconds(8))
                .build();
    }

    @SuppressWarnings("unchecked")
    public PlaceSearchResponseDto search(String query) {
        String trimmed = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (trimmed.length() < 2) {
            return response("NO_RESULTS", "Enter at least 2 characters.", List.of());
        }

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/search")
                .queryParam("q", trimmed)
                .queryParam("format", "jsonv2")
                .queryParam("addressdetails", "1")
                .queryParam("limit", "8")
                .queryParam("accept-language", "en")
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, userAgent);

        try {
            log.info("Nominatim Search Fetching query={}", trimmed);
            ResponseEntity<List> entity = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), List.class);
            List<Map<String, Object>> rawResults = entity.getBody() == null ? List.of() : (List<Map<String, Object>>) entity.getBody();
            List<PlaceSearchResultDto> results = rawResults.stream()
                    .map(this::mapResult)
                    .filter(Objects::nonNull)
                    .limit(6)
                    .toList();

            if (results.isEmpty()) {
                log.info("Nominatim Search NoResults query={}", trimmed);
                return response("NO_RESULTS", "No matching place found.", List.of());
            }
            log.info("Nominatim Search Success query={} results={}", trimmed, results.size());
            return response("SUCCESS", "OK", results);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("Nominatim Search RateLimited query={} reason={}", trimmed, e.getMessage());
                return response("RATE_LIMITED", "Nominatim rate limit reached. Please wait and try again.", List.of());
            }
            log.warn("Nominatim Search Failed query={} status={} reason={}", trimmed, e.getStatusCode().value(), e.getMessage());
            return response("FAILED", "Place search provider unavailable.", List.of());
        } catch (RestClientException e) {
            log.warn("Nominatim Search Failed query={} reason={}", trimmed, e.getMessage());
            return response("FAILED", "Place search provider unavailable.", List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private PlaceSearchResultDto mapResult(Map<String, Object> raw) {
        Double lat = number(raw.get("lat"));
        Double lon = number(raw.get("lon"));
        if (lat == null || lon == null) {
            return null;
        }
        Map<String, Object> address = raw.get("address") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String cityName = firstText(address, "city", "town", "village", "municipality", "county", "state_district");
        String displayName = text(raw.get("display_name"));
        return PlaceSearchResultDto.builder()
                .placeId(text(raw.get("place_id")))
                .displayName(displayName)
                .cityName(cityName.isBlank() ? firstDisplayPart(displayName) : cityName)
                .state(firstText(address, "state", "region"))
                .country(text(address.get("country")))
                .latitude(lat)
                .longitude(lon)
                .type(text(raw.get("type")))
                .importance(number(raw.get("importance")))
                .build();
    }

    private PlaceSearchResponseDto response(String status, String message, List<PlaceSearchResultDto> results) {
        return PlaceSearchResponseDto.builder()
                .status(status)
                .message(message)
                .results(results)
                .build();
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String firstDisplayPart(String displayName) {
        if (displayName == null || displayName.isBlank()) return "Selected Place";
        return displayName.split(",")[0].trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
