package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitTransferConfigTest {
    @Test void absentAndMalformedSectionsKeepDefaults() {
        assertEquals(GitTransferConfig.DEFAULT, GitTransferConfig.of(null));
        assertEquals(GitTransferConfig.DEFAULT, GitTransferConfig.of("5m"));
        assertEquals(GitTransferConfig.DEFAULT, GitTransferConfig.of(Map.of("stall", "soon", "timeout", Map.of())));
    }

    @Test void zeroTurnsABoundOff() {
        var config = GitTransferConfig.of(Map.of("stall", 0, "timeout", "2h"));
        assertNull(config.stall());
        assertEquals(Duration.ofHours(2), config.timeout());
        assertNull(GitTransferConfig.of(Map.of("timeout", "0")).timeout());
    }

    @Test void readFromConfigYaml() {
        var config = new SpawnConfig();
        config.setConfigByPath("git-transfer.stall", "90s");
        assertEquals(new GitTransferConfig(Duration.ofSeconds(90), GitTransferConfig.DEFAULT.timeout()), config.gitTransfer());
    }
}
