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

    /** One arch's VM checksum is not a pin: the other arch would download the image unverified. */
    @Test
    void aPinWithTheVmImageForOneArchLeavesTheVmImageOut(@TempDir Path home) {
        var yaml = pin(home, new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1")));
        assertFalse(yaml.contains("vm_image_url"), yaml);
        assertFalse(yaml.contains("vm_image_sha256"), yaml);
    }

    /** The same for the container image, which every template starts from: the pin is refused. */
    @Test
    void aReleaseWithTheContainerImageForOneArchIsNotPinned(@TempDir Path home) throws Exception {
        var release = new dev.incusspawn.baseimage.BaseImageReleases.Release("fedora-44-20261020", "2026-10-20", "sums", null);
        var releases = org.mockito.Mockito.mock(dev.incusspawn.baseimage.BaseImageReleases.class);
        org.mockito.Mockito.when(releases.fetchChecksums(org.mockito.ArgumentMatchers.eq(release),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Checksums(Map.of("x86_64", "c1"), Map.of("x86_64", "v1", "aarch64", "v2")));
        InitCommandTest.withHome(home, () -> {
            try {
                var cmd = new UpdateBaseCommand();
                cmd.releases = releases;
                var result = cmd.pinToTag("fedora-44-20261020", java.util.List.of(release),
                        ImageDef.loadBuiltinByName("tpl-minimal"));
                assertNotEquals(org.aesh.command.CommandResult.SUCCESS, result);
                assertFalse(Files.exists(ImageDef.userImagesDir().resolve("minimal.yaml")));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** A pin left without a VM image by an earlier pin takes the built-in's VM image again, so the loss does not stick. */
    @Test
    void aPinAfterOneWithoutAVmImageNamesTheBuiltInVmImageAgain(@TempDir Path home) throws Exception {
        var release = new dev.incusspawn.baseimage.BaseImageReleases.Release("fedora-44-20261020", "2026-10-20", "sums", null);
        var releases = org.mockito.Mockito.mock(dev.incusspawn.baseimage.BaseImageReleases.class);
        org.mockito.Mockito.when(releases.fetchChecksums(org.mockito.ArgumentMatchers.eq(release),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1", "aarch64", "v2")));
        InitCommandTest.withHome(home, () -> {
            try {
                var earlierPin = ImageDef.loadBuiltinByName("tpl-minimal");
                earlierPin.setVmImageUrl(null);
                var cmd = new UpdateBaseCommand();
                cmd.releases = releases;
                assertEquals(org.aesh.command.CommandResult.SUCCESS,
                        cmd.pinToTag("fedora-44-20261020", java.util.List.of(release), earlierPin));
                var yaml = Files.readString(ImageDef.userImagesDir().resolve("minimal.yaml"));
                assertTrue(yaml.contains("vm_image_url: " + ImageDef.loadBuiltinByName("tpl-minimal").getVmImageUrl()), yaml);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** A minimal.yaml of the user's own, on another repository, keeps its choice of no VM image. */
    @Test
    void aPinOfTheUsersOwnDefinitionGetsNoBuiltInVmImage(@TempDir Path home) throws Exception {
        var release = new dev.incusspawn.baseimage.BaseImageReleases.Release("fedora-44-20261020", "2026-10-20", "sums", null);
        var releases = org.mockito.Mockito.mock(dev.incusspawn.baseimage.BaseImageReleases.class);
        org.mockito.Mockito.when(releases.fetchChecksums(org.mockito.ArgumentMatchers.eq(release),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1", "aarch64", "v2")));
        InitCommandTest.withHome(home, () -> {
            try {
                var own = ImageDef.loadBuiltinByName("tpl-minimal");
                own.setImageUrl("https://github.com/someone/images/releases/download/{tag}/fedora-44-{arch}.tar.xz");
                own.setVmImageUrl(null);
                var cmd = new UpdateBaseCommand();
                cmd.releases = releases;
                assertEquals(org.aesh.command.CommandResult.SUCCESS,
                        cmd.pinToTag("fedora-44-20261020", java.util.List.of(release), own));
                var yaml = Files.readString(ImageDef.userImagesDir().resolve("minimal.yaml"));
                assertFalse(yaml.contains("vm_image_url"), yaml);
                org.mockito.Mockito.verify(releases).fetchChecksums(release, own.getImageUrl(), null);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
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
