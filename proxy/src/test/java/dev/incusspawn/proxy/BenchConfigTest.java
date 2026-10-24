package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ISX_BENCH_CONFIG}: bench/run.sh's model loads run a proxy on accounts of their own, and the
 * user's config.yaml is neither read nor written by it.
 */
class BenchConfigTest {

    @TempDir
    Path home;
    private String originalHome;
    private Path userConfig;

    @BeforeEach
    void isolateHome() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        userConfig = SpawnConfig.configDir().resolve("config.yaml");
        Files.createDirectories(userConfig.getParent());
        Files.writeString(userConfig, """
                claude:
                  default: mine
                  accounts:
                    mine:
                      type: api-key
                      apiKey: the-users-own
                """);
    }

    @AfterEach
    void restore() {
        SpawnConfig.useBenchFile(null);
        System.setProperty("user.home", originalHome);
    }

    @Test
    void theProxyReadsAndWritesTheBenchConfigInsteadOfTheUsers() throws Exception {
        var bench = home.resolve("bench-config.yaml");
        Files.writeString(bench, """
                claude:
                  default: isx-bench
                  accounts:
                    isx-bench:
                      type: api-key
                      apiKey: bench-key
                """);
        var before = Files.readString(userConfig);

        assertTrue(ProxyMain.applyBenchConfig(bench.toString()));

        var config = SpawnConfig.load();
        assertEquals("isx-bench", config.getClaude().getDefaultAccount());
        assertEquals("bench-key", config.getClaude().getAccounts().get("isx-bench").getApiKey());
        config.save();
        assertEquals(before, Files.readString(userConfig));
        assertTrue(Files.readString(bench).contains("bench-key"));
    }

    @Test
    void driftIsJudgedOnTheBenchConfigTheProxyRead() throws Exception {
        var bench = home.resolve("bench-config.yaml");
        Files.writeString(bench, "claude:\n  default: isx-bench\n");
        assertTrue(ProxyMain.applyBenchConfig(bench.toString()));
        var loaded = ConfigFingerprint.load();

        Files.writeString(userConfig, "claude:\n  default: edited-by-the-user\n");
        assertTrue(loaded.fingerprint().isCurrent(), "the user's file is not what the proxy serves");
        Files.writeString(bench, "claude:\n  default: isx-bench-edited\n");
        assertFalse(loaded.fingerprint().isCurrent());
    }

    @Test
    void aMissingBenchConfigRefusesToStartRatherThanServeTheUsersAccounts() {
        assertFalse(ProxyMain.applyBenchConfig(home.resolve("no-such.yaml").toString()));
        assertEquals("mine", SpawnConfig.load().getClaude().getDefaultAccount());
    }

    @Test
    void unsetLeavesTheUsersConfig() {
        assertTrue(ProxyMain.applyBenchConfig(""));
        assertEquals("mine", SpawnConfig.load().getClaude().getDefaultAccount());
    }
}
