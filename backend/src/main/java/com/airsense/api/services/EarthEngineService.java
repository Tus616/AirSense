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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EarthEngineService {

    @Value("${google.application.credentials:}")
    private String credentialsPath;
    
    @Value("${google.cloud.project.id:}")
    private String projectId;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EarthEngineService(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getSatelliteInsights(double lat, double lon) {
        if (credentialsPath == null || credentialsPath.isEmpty() || projectId == null || projectId.isEmpty()) {
            log.warn("EarthEngine fallback reason=missing_credentials_or_project lat={} lon={}", lat, lon);
            return generateFallbackInsights("Credentials or project ID not configured");
        }

        try {
            log.info("EarthEngine request lat={} lon={} projectId={}", lat, lon, projectId);
            JsonNode credentials = objectMapper.readTree(Files.readString(Path.of(credentialsPath)));
            String accessToken = fetchAccessToken(credentials);
            List<Map<String, Object>> datasets = List.of(
                    fetchPublicAsset("NO2", "COPERNICUS/S5P/OFFL/L3_NO2", accessToken),
                    fetchPublicAsset("SO2", "COPERNICUS/S5P/OFFL/L3_SO2", accessToken),
                    fetchPublicAsset("CO", "COPERNICUS/S5P/OFFL/L3_CO", accessToken),
                    fetchPublicAsset("O3", "COPERNICUS/S5P/OFFL/L3_O3", accessToken),
                    fetchPublicAsset("NDVI", "MODIS/061/MOD13A2", accessToken),
                    fetchPublicAsset("LST", "MODIS/061/MOD11A1", accessToken)
            );
            Map<String, Object> measurements = new HashMap<>();
            String measurementReason = null;
            try {
                measurements = fetchSatelliteMeasurements(lat, lon, accessToken);
            } catch (Exception measurementError) {
                measurementReason = measurementError.getMessage();
                log.warn("EarthEngine measurement fallback reason={} lat={} lon={}", measurementReason, lat, lon);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("no2", measurements.get("no2"));
            result.put("so2", measurements.get("so2"));
            result.put("co", measurements.get("co"));
            result.put("o3", measurements.get("o3"));
            result.put("aerosolIndex", measurements.get("aerosolIndex"));
            result.put("vegetationIndex", measurements.get("vegetationIndex"));
            result.put("thermalAnomaly", measurements.get("thermalAnomaly"));
            result.put("confidence", measurements.isEmpty() ? 60 : 85);
            result.put("provider", "Google Earth Engine");
            result.put("available", true);
            result.put("fallback", false);
            result.put("measurementFallback", measurementReason != null);
            result.put("measurementFallbackReason", measurementReason);
            result.put("projectId", projectId);
            result.put("location", Map.of("latitude", lat, "longitude", lon));
            result.put("datasetsVerified", datasets);
            result.put("timestamp", Instant.now().toString());
            log.info("EarthEngine success lat={} lon={} datasetsVerified={}", lat, lon, datasets.size());
            return result;
        } catch (Exception e) {
            log.warn("EarthEngine fallback reason={} lat={} lon={}", e.getMessage(), lat, lon);
            return generateFallbackInsights(e.getMessage());
        }
    }

    private String fetchAccessToken(JsonNode credentials) throws Exception {
        String clientEmail = credentials.path("client_email").asText("");
        String privateKeyPem = credentials.path("private_key").asText("");
        String tokenUri = credentials.path("token_uri").asText("https://oauth2.googleapis.com/token");

        if (clientEmail.isBlank() || privateKeyPem.isBlank()) {
            throw new IllegalStateException("Service account JSON missing client_email or private_key");
        }

        String assertion = createSignedJwt(clientEmail, privateKeyPem, tokenUri);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        body.add("assertion", assertion);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(tokenUri, new HttpEntity<>(body, headers), JsonNode.class);
        JsonNode responseBody = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || responseBody == null || responseBody.path("access_token").asText("").isBlank()) {
            throw new IllegalStateException("Unable to obtain Google access token");
        }
        log.info("EarthEngine token success clientEmail={}", clientEmail);
        return responseBody.path("access_token").asText();
    }

    private Map<String, Object> fetchPublicAsset(String pollutant, String assetId, String accessToken) {
        String url = "https://earthengine.googleapis.com/v1/projects/earthengine-public/assets/" + assetId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        JsonNode body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null) {
            throw new IllegalStateException("Unable to read Earth Engine asset " + assetId);
        }
        return Map.of(
                "pollutant", pollutant,
                "assetId", assetId,
                "name", body.path("name").asText(assetId),
                "type", body.path("type").asText("UNKNOWN")
        );
    }

    private Map<String, Object> fetchSatelliteMeasurements(double lat, double lon, String accessToken) {
        String url = "https://earthengine.googleapis.com/v1/projects/" + projectId + "/value:compute";
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(7);
        String script = String.format("""
                var point = ee.Geometry.Point([%f, %f]);
                var region = point.buffer(5000);
                var start = ee.Date('%s');
                var end = ee.Date('%s');
                function meanValue(collectionId, bandName) {
                  return ee.ImageCollection(collectionId)
                    .select(bandName)
                    .filterDate(start, end)
                    .filterBounds(point)
                    .mean()
                    .reduceRegion({
                      reducer: ee.Reducer.mean(),
                      geometry: region,
                      scale: 1000,
                      maxPixels: 1000000000
                    }).get(bandName);
                }
                function scaledMean(collectionId, bandName, scaleFactor, offset) {
                  var raw = meanValue(collectionId, bandName);
                  return ee.Number(raw).multiply(scaleFactor).add(offset);
                }
                ({
                  no2: meanValue('COPERNICUS/S5P/OFFL/L3_NO2', 'tropospheric_NO2_column_number_density'),
                  so2: meanValue('COPERNICUS/S5P/OFFL/L3_SO2', 'SO2_column_number_density'),
                  co: meanValue('COPERNICUS/S5P/OFFL/L3_CO', 'CO_column_number_density'),
                  o3: meanValue('COPERNICUS/S5P/OFFL/L3_O3', 'O3_column_number_density'),
                  aerosolIndex: meanValue('COPERNICUS/S5P/OFFL/L3_AER_AI', 'absorbing_aerosol_index'),
                  vegetationIndex: scaledMean('MODIS/061/MOD13A2', 'NDVI', 0.0001, 0),
                  thermalAnomaly: scaledMean('MODIS/061/MOD11A1', 'LST_Day_1km', 0.02, -273.15)
                })
                """, lon, lat, start, end);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("expression", script);

        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        JsonNode responseBody = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || responseBody == null) {
            throw new IllegalStateException("Unable to compute Earth Engine satellite values");
        }

        JsonNode resultNode = responseBody.path("result");
        Map<String, Object> result = new HashMap<>();
        putNullableDouble(result, "no2", resultNode.path("no2"));
        putNullableDouble(result, "so2", resultNode.path("so2"));
        putNullableDouble(result, "co", resultNode.path("co"));
        putNullableDouble(result, "o3", resultNode.path("o3"));
        putNullableDouble(result, "aerosolIndex", resultNode.path("aerosolIndex"));
        putNullableDouble(result, "vegetationIndex", resultNode.path("vegetationIndex"));
        putNullableDouble(result, "thermalAnomaly", resultNode.path("thermalAnomaly"));
        log.info("EarthEngine value compute success lat={} lon={}", lat, lon);
        return result;
    }

    private void putNullableDouble(Map<String, Object> result, String key, JsonNode node) {
        result.put(key, node != null && node.isNumber() ? node.doubleValue() : null);
    }

    private String createSignedJwt(String clientEmail, String privateKeyPem, String tokenUri) throws Exception {
        Instant now = Instant.now();
        String header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = base64Url(objectMapper.writeValueAsBytes(Map.of(
                "iss", clientEmail,
                "scope", "https://www.googleapis.com/auth/earthengine.readonly",
                "aud", tokenUri,
                "iat", now.getEpochSecond(),
                "exp", now.plus(Duration.ofMinutes(55)).getEpochSecond()
        )));
        String unsignedJwt = header + "." + claims;

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(parsePrivateKey(privateKeyPem));
        signature.update(unsignedJwt.getBytes(StandardCharsets.UTF_8));
        return unsignedJwt + "." + base64Url(signature.sign());
    }

    private PrivateKey parsePrivateKey(String privateKeyPem) throws Exception {
        String normalized = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    private Map<String, Object> generateFallbackInsights(String reason) {
        Map<String, Object> result = new HashMap<>();
        result.put("no2", null);
        result.put("so2", null);
        result.put("co", null);
        result.put("o3", null);
        result.put("vegetationIndex", null);
        result.put("thermalAnomaly", null);
        result.put("confidence", 0);
        result.put("provider", "Google Earth Engine");
        result.put("available", false);
        result.put("fallback", true);
        result.put("reason", reason);
        result.put("timestamp", Instant.now().toString());
        return result;
    }
}
