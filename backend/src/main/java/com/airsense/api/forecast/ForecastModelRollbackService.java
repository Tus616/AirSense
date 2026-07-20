package com.airsense.api.forecast;

import com.airsense.api.entities.ForecastModelRegistryEntry;
import com.airsense.api.repositories.ForecastModelRegistryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ForecastModelRollbackService {
    private final ForecastModelRegistryRepository repository;

    public synchronized Map<String, Object> rollback(String aqiStandard, Integer horizonHours, String modelScope, String targetVersion) {
        ForecastModelRegistryEntry current = repository
                .findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc(aqiStandard, horizonHours, modelScope)
                .orElseThrow(() -> new IllegalStateException("No active promoted model exists for requested scope"));
        String rollbackVersion = valueOrDefault(targetVersion, current.getPreviousPromotedVersion());
        if (rollbackVersion == null || rollbackVersion.isBlank()) {
            throw new IllegalStateException("Missing previous promoted version");
        }
        ForecastModelRegistryEntry candidate = repository
                .findByAqiStandardAndHorizonHoursAndModelScopeAndVersion(aqiStandard, horizonHours, modelScope, rollbackVersion)
                .orElseThrow(() -> new IllegalStateException("Previous promoted version not found"));
        validateCandidate(current, candidate);

        Instant now = Instant.now();
        candidate.setActive(true);
        candidate.setPromotionStatus("PROMOTED");
        candidate.setActivatedAt(now);
        candidate.setDeactivatedAt(null);
        candidate.setPreviousPromotedVersion(current.getVersion());
        candidate.setRollbackReason("ROLLBACK_ACTIVATED_FROM_" + current.getVersion());
        candidate.setUpdatedAt(now);

        current.setActive(false);
        current.setDeactivatedAt(now);
        current.setRollbackReason("ROLLED_BACK_TO_" + candidate.getVersion());
        current.setUpdatedAt(now);

        repository.saveAll(List.of(candidate, current));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ROLLED_BACK");
        response.put("activatedVersion", candidate.getVersion());
        response.put("deactivatedVersion", current.getVersion());
        response.put("aqiStandard", aqiStandard);
        response.put("horizonHours", horizonHours);
        response.put("modelScope", modelScope);
        response.put("activatedAt", now);
        return response;
    }

    private void validateCandidate(ForecastModelRegistryEntry current, ForecastModelRegistryEntry candidate) {
        if (!equals(current.getAqiStandard(), candidate.getAqiStandard())
                || !equals(current.getHorizonHours(), candidate.getHorizonHours())
                || !equals(current.getModelScope(), candidate.getModelScope())) {
            throw new IllegalStateException("Rollback candidate scope, standard, or horizon does not match current model");
        }
        if (!Boolean.TRUE.equals(candidate.getRollbackEligible())) {
            throw new IllegalStateException("Rollback candidate is not marked rollback eligible");
        }
        String checksum = candidate.getArtifactChecksum();
        if (checksum != null && !checksum.isBlank()) {
            String actual = checksum(candidate.getArtifactPath());
            if (!checksum.equalsIgnoreCase(actual)) {
                throw new IllegalStateException("Artifact checksum mismatch");
            }
        }
    }

    private String checksum(String artifactPath) {
        try {
            Path path = Path.of(artifactPath);
            if (!path.isAbsolute()) {
                path = Path.of("..", "ai-service").resolve(path).normalize();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Artifact unavailable: " + e.getMessage());
        }
    }

    private boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
