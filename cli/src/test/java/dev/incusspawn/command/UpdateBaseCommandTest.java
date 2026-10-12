package dev.incusspawn.command;

import dev.incusspawn.baseimage.BaseImageReleases.Checksums;
import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpdateBaseCommandTest {

    private static String pin(Path home, Checksums sums) {
        var out = new String[1];
        InitCommandTest.withHome(home, () -> {
            try {
                var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
                new UpdateBaseCommand().writeUserOverride(builtin, "fedora-44-20261020", sums);
                out[0] = Files.readString(ImageDef.userImagesDir().resolve("minimal.yaml"));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        return out[0];
    }

    @Test
    void aPinCarriesTheVmImageWithItsChecksums(@TempDir Path home) {
        var yaml = pin(home, new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1", "aarch64", "v2")));
        assertTrue(yaml.contains("vm_image_url: "), yaml);
        assertTrue(yaml.contains("vm_image_sha256:\n  aarch64: v2\n  x86_64: v1\n"), yaml);
    }

    /** A release without the VM image the built-in names: pinning its URL with no checksum would download it unverified. */
    @Test
    void aPinWithoutVmChecksumsLeavesTheVmImageOut(@TempDir Path home) {
        var yaml = pin(home, new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of()));
        assertFalse(yaml.contains("vm_image_url"), yaml);
        assertFalse(yaml.contains("vm_image_sha256"), yaml);
        assertTrue(yaml.contains("image_sha256:\n  aarch64: c2\n  x86_64: c1\n"), yaml);
    }
}
