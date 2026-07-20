package com.airsense.api.forecast;

import com.airsense.api.entities.ForecastModelRegistryEntry;
import com.airsense.api.repositories.ForecastModelRegistryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastModelRollbackServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void successfulRollbackActivatesPreviousValidatedArtifact() throws Exception {
        ForecastModelRegistryRepository repository = mock(ForecastModelRegistryRepository.class);
        Path artifact = artifact("model-v1", "payload");
        ForecastModelRegistryEntry current = active("v2", "v1");
        ForecastModelRegistryEntry previous = previous("v1", artifact, sha256(artifact));
        when(repository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc("INDIA_NAQI", 24, "lalbagh"))
                .thenReturn(Optional.of(current));
        when(repository.findByAqiStandardAndHorizonHoursAndModelScopeAndVersion("INDIA_NAQI", 24, "lalbagh", "v1"))
                .thenReturn(Optional.of(previous));

        var response = new ForecastModelRollbackService(repository).rollback("INDIA_NAQI", 24, "lalbagh", null);

        assertThat(response.get("status")).isEqualTo("ROLLED_BACK");
        assertThat(previous.getActive()).isTrue();
        assertThat(current.getActive()).isFalse();
        verify(repository).saveAll(anyList());
    }

    @Test
    void checksumFailureDoesNotSave() throws Exception {
        ForecastModelRegistryRepository repository = repositoryWith(active("v2", "v1"),
                previous("v1", artifact("model-v1", "payload"), "bad-checksum"));

        assertThatThrownBy(() -> new ForecastModelRollbackService(repository).rollback("INDIA_NAQI", 24, "lalbagh", null))
                .hasMessageContaining("checksum mismatch");
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void scopeMismatchDoesNotSave() throws Exception {
        ForecastModelRegistryRepository repository = mock(ForecastModelRegistryRepository.class);
        ForecastModelRegistryEntry current = active("v2", "v1");
        ForecastModelRegistryEntry previous = previous("v1", artifact("model-v1", "payload"), null);
        previous.setModelScope("gomti_nagar");
        when(repository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc("INDIA_NAQI", 24, "lalbagh"))
                .thenReturn(Optional.of(current));
        when(repository.findByAqiStandardAndHorizonHoursAndModelScopeAndVersion("INDIA_NAQI", 24, "lalbagh", "v1"))
                .thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> new ForecastModelRollbackService(repository).rollback("INDIA_NAQI", 24, "lalbagh", null))
                .hasMessageContaining("does not match");
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void missingPreviousVersionFailsBeforeSave() {
        ForecastModelRegistryRepository repository = mock(ForecastModelRegistryRepository.class);
        when(repository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc("INDIA_NAQI", 24, "lalbagh"))
                .thenReturn(Optional.of(active("v2", null)));

        assertThatThrownBy(() -> new ForecastModelRollbackService(repository).rollback("INDIA_NAQI", 24, "lalbagh", null))
                .hasMessageContaining("Missing previous promoted version");
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void persistenceFailureReportsError() throws Exception {
        ForecastModelRegistryRepository repository = repositoryWith(active("v2", "v1"),
                previous("v1", artifact("model-v1", "payload"), null));
        when(repository.saveAll(anyList())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> new ForecastModelRollbackService(repository).rollback("INDIA_NAQI", 24, "lalbagh", null))
                .hasMessageContaining("database down");
    }

    private ForecastModelRegistryRepository repositoryWith(ForecastModelRegistryEntry current, ForecastModelRegistryEntry previous) {
        ForecastModelRegistryRepository repository = mock(ForecastModelRegistryRepository.class);
        when(repository.findFirstByAqiStandardAndHorizonHoursAndModelScopeAndActiveTrueOrderByPromotedAtDesc("INDIA_NAQI", 24, "lalbagh"))
                .thenReturn(Optional.of(current));
        when(repository.findByAqiStandardAndHorizonHoursAndModelScopeAndVersion("INDIA_NAQI", 24, "lalbagh", "v1"))
                .thenReturn(Optional.of(previous));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0, List.class));
        return repository;
    }

    private ForecastModelRegistryEntry active(String version, String previousVersion) {
        return ForecastModelRegistryEntry.builder()
                .aqiStandard("INDIA_NAQI")
                .horizonHours(24)
                .modelScope("lalbagh")
                .version(version)
                .previousPromotedVersion(previousVersion)
                .promotionStatus("PROMOTED")
                .active(true)
                .promotedAt(Instant.now())
                .build();
    }

    private ForecastModelRegistryEntry previous(String version, Path artifact, String checksum) {
        return ForecastModelRegistryEntry.builder()
                .aqiStandard("INDIA_NAQI")
                .horizonHours(24)
                .modelScope("lalbagh")
                .version(version)
                .artifactPath(artifact.toString())
                .artifactChecksum(checksum)
                .rollbackEligible(true)
                .promotionStatus("VALIDATED")
                .active(false)
                .build();
    }

    private Path artifact(String name, String content) throws Exception {
        Path path = tempDir.resolve(name + ".bin");
        Files.writeString(path, content);
        return path;
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
