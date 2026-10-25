package dev.incusspawn.command;

import dev.incusspawn.command.BaseImageAge.Standing;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which base image an instance runs, read from what Incus copied onto it from the image (#1657),
 * against the root definition a rebuild would use now.
 */
class BaseImageAgeTest {

    private static final String CURRENT_SHA = "aa".repeat(32);
    private static final String CURRENT_VM_SHA = "bb".repeat(32);

    private static ImageDef def(String yaml) {
        try {
            return ImageDef.parseYaml(yaml);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final ImageDef MINIMAL = def("""
            name: tpl-minimal
            image: fedora-44-base
            image_url: https://example.org/{tag}/fedora-44-{arch}.tar.xz
            vm_image_url: https://example.org/{tag}/fedora-44-{arch}-vm-direct.tar.xz
            image_tag: fedora-44-20261021
            image_sha256: {x86_64: %s, aarch64: %s}
            vm_image_sha256: {x86_64: %s, aarch64: %s}
            """.formatted(CURRENT_SHA, "cc".repeat(32), CURRENT_VM_SHA, "dd".repeat(32)));

    private static final ImageDef JAVA = def("""
            name: tpl-java
            parent: tpl-minimal
            """);

    private static final ImageDef LOCAL_ROOT = def("""
            name: tpl-local
            image: my-own-image
            """);

    private static final Map<String, ImageDef> DEFS = Map.of(
            "tpl-minimal", MINIMAL, "tpl-java", JAVA, "tpl-local", LOCAL_ROOT);

    private static JqValue instance(String type, String arch, Map<String, String> config) {
        var json = new StringBuilder("{\"name\":\"i\",\"type\":\"" + type + "\",\"architecture\":\"" + arch
                + "\",\"config\":{");
        var first = true;
        for (var e : config.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        return JqValues.parseStrict(json.append("}}").toString());
    }

    private static JqValue container(String profile, String tag, String fingerprint) {
        var config = new LinkedHashMap<String, String>();
        config.put(Metadata.PROFILE, profile);
        if (tag != null) config.put(BaseImageAge.TAG, tag);
        if (fingerprint != null) config.put(BaseImageAge.FINGERPRINT, fingerprint);
        return instance("container", "x86_64", config);
    }

    private static Standing standing(JqValue instance) {
        var age = BaseImageAge.assess(instance, DEFS);
        return age == null ? null : age.standing();
    }

    @Test
    void theFileTheDefinitionPinsIsCurrentWhateverTagItWasImportedUnder() {
        // A release that re-published an unchanged asset: the image keeps its first import's tag
        assertEquals(Standing.CURRENT, standing(container("tpl-java", "fedora-44-20261001", CURRENT_SHA)));
    }

    @Test
    void anEarlierReleaseIsOlder() {
        var age = BaseImageAge.assess(container("tpl-java", "fedora-44-20261001", "ee".repeat(32)), DEFS);
        assertEquals(Standing.OLDER, age.standing());
        assertEquals("fedora-44-20261001", age.tag());
        assertEquals("fedora-44-20261021", age.expectedTag());
    }

    @Test
    void anEarlierFedoraReleaseIsOlderWhateverItsDate() {
        assertEquals(Standing.OLDER, standing(container("tpl-java", "fedora-43-20261101", "ee".repeat(32))));
    }

    @Test
    void aLaterReleaseATrackedBuildTookIsNewer() {
        assertEquals(Standing.NEWER, standing(container("tpl-java", "fedora-44-20261024", "ee".repeat(32))));
    }

    @Test
    void theSameTagUnderAnotherFileIsCurrent() {
        assertEquals(Standing.CURRENT, standing(container("tpl-java", "fedora-44-20261021", "ee".repeat(32))));
    }

    @Test
    void noTagOrOneThatDoesNotParseIsUnknown() {
        assertEquals(Standing.UNKNOWN, standing(container("tpl-java", null, "ee".repeat(32))));
        assertEquals(Standing.UNKNOWN, standing(container("tpl-java", "nightly", "ee".repeat(32))));
        assertEquals(Standing.UNKNOWN, standing(container("tpl-java", "ubuntu-24-20261001", "ee".repeat(32))));
    }

    @Test
    void aVmIsComparedWithTheVmDisk() {
        var vm = instance("virtual-machine", "x86_64", Map.of(Metadata.PROFILE, "tpl-java",
                BaseImageAge.TAG, "fedora-44-20261001", BaseImageAge.FINGERPRINT, CURRENT_VM_SHA));
        assertEquals(Standing.CURRENT, standing(vm));
    }

    @Test
    void theFingerprintOfAnotherArchitectureDoesNotCount() {
        var arm = instance("container", "aarch64", Map.of(Metadata.PROFILE, "tpl-java",
                BaseImageAge.TAG, "fedora-44-20261001", BaseImageAge.FINGERPRINT, CURRENT_SHA));
        assertEquals(Standing.OLDER, standing(arm));
    }

    @Test
    void anInstanceWithNoProfileStampIsJudgedByItsParentTemplate() {
        var bare = instance("container", "x86_64", Map.of(Metadata.PARENT, "tpl-java",
                BaseImageAge.TAG, "fedora-44-20261001", BaseImageAge.FINGERPRINT, "ee".repeat(32)));
        assertEquals(Standing.OLDER, standing(bare));
    }

    @Test
    void aRootWithNoDownloadedImageOrAnUnknownTemplateIsNotAssessed() {
        assertNull(BaseImageAge.assess(container("tpl-local", "fedora-44-20261001", null), DEFS));
        assertNull(BaseImageAge.assess(container("tpl-gone", "fedora-44-20261001", null), DEFS));
        assertNull(BaseImageAge.assess(instance("container", "x86_64", Map.of()), DEFS));
    }

    @Test
    void aPinnedOlderReleaseIsWhatARebuildUsesSoItIsCurrent() {
        // The pin is applied at load: the definition handed in is the one in effect
        var pinned = def("""
                name: tpl-minimal
                image: fedora-44-base
                image_url: https://example.org/{tag}/fedora-44-{arch}.tar.xz
                image_tag: fedora-44-20261001
                pinned: true
                """);
        var defs = Map.of("tpl-minimal", pinned, "tpl-java", JAVA);
        assertEquals(Standing.CURRENT,
                BaseImageAge.assess(container("tpl-java", "fedora-44-20261001", "ee".repeat(32)), defs).standing());
    }

    @Test
    void tagsCompareReleaseFirstThenDate() {
        assertTrue(BaseImageAge.compareTags("fedora-44-20261001", "fedora-44-20261021") < 0);
        assertTrue(BaseImageAge.compareTags("fedora-45-20260101", "fedora-44-20261021") > 0);
        assertEquals(0, BaseImageAge.compareTags("fedora-44-20261021", "fedora-44-20261021"));
        assertNull(BaseImageAge.compareTags("fedora-44-20261021", "centos-10-20261021"));
        assertNull(BaseImageAge.compareTags("fedora-44", "fedora-44-20261021"));
        for (var bad : List.of("", "-", "fedora--20261001", "fedora-44-2026100", "fedora-99999999999999999999-20261010")) {
            assertNull(BaseImageAge.compareTags(bad, "fedora-44-20261021"), bad);
        }
    }

    @Test
    void aKnownFixIsMissingOnlyWhenTheReleaseIsBeforeItAndARebuildWouldBringIt() {
        var fixes = List.of(new BaseImageAge.KnownFix("fedora-44-20261009", "breaks"));
        var before = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261001", "fedora-44-20261021");
        assertEquals(fixes, BaseImageAge.missingFixes(before, fixes));
        var after = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261015", "fedora-44-20261021");
        assertEquals(List.of(), BaseImageAge.missingFixes(after, fixes), "a routine newer release");
        var rebuildLacksItToo = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261001", "fedora-44-20261005");
        assertEquals(List.of(), BaseImageAge.missingFixes(rebuildLacksItToo, fixes), "a pin before the fix");
        var onTheFix = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261001", "fedora-44-20261009");
        assertEquals(fixes, BaseImageAge.missingFixes(onTheFix, fixes));
        var current = new BaseImageAge.Assessment(Standing.CURRENT, "fedora-44-20261001", "fedora-44-20261021");
        assertEquals(List.of(), BaseImageAge.missingFixes(current, fixes), "the same file as the current release");
        assertEquals(List.of(), BaseImageAge.missingFixes(null, fixes));
    }

    @Test
    void theWatchdogFixIsKnown() {
        var age = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261001", "fedora-44-20261021");
        // incus-spawn-images 2f77305, first published in fedora-44-20261009
        assertEquals(List.of("fedora-44-20261009"),
                BaseImageAge.missingFixes(age).stream().map(BaseImageAge.KnownFix::release).toList());
        var onTheFixedRelease = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261009", "fedora-44-20261021");
        assertEquals(List.of(), BaseImageAge.missingFixes(onTheFixedRelease), "that release has the fix");
        var dayBefore = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261008", "fedora-44-20261021");
        assertEquals(1, BaseImageAge.missingFixes(dayBefore).size());
    }

    @Test
    void aSecondReleaseOnOneDayOrdersAfterTheFirstAndBeforeTheNextDay() {
        // release-tag.sh names a later release the same day fedora-44-20261021-2, -3 ...
        assertTrue(BaseImageAge.compareTags("fedora-44-20261021-2", "fedora-44-20261021") > 0);
        assertTrue(BaseImageAge.compareTags("fedora-44-20261021", "fedora-44-20261021-2") < 0);
        assertTrue(BaseImageAge.compareTags("fedora-44-20261021-2", "fedora-44-20261022") < 0);
        assertTrue(BaseImageAge.compareTags("fedora-44-20261021-10", "fedora-44-20261021-3") > 0, "by number, not text");
        assertEquals(0, BaseImageAge.compareTags("fedora-44-20261021-2", "fedora-44-20261021-2"));
        assertTrue(BaseImageAge.sameLine("fedora-44-20261021-2", "fedora-44-20261009"));

        var age = new BaseImageAge.Assessment(Standing.OLDER, "fedora-44-20261001", "fedora-44-20261021-2");
        assertEquals(List.of("fedora-44-20261009"),
                BaseImageAge.missingFixes(age).stream().map(BaseImageAge.KnownFix::release).toList());
        // And an instance on such a release is judged against a definition naming it
        assertEquals(Standing.OLDER, BaseImageAge.assess(container("tpl-java", "fedora-44-20261021-2", "ee".repeat(32)),
                Map.of("tpl-minimal", def("""
                        name: tpl-minimal
                        image: fedora-44-base
                        image_url: https://example.org/{tag}/fedora-44-{arch}.tar.xz
                        image_tag: fedora-44-20261021-3
                        """), "tpl-java", JAVA)).standing());
    }
}
