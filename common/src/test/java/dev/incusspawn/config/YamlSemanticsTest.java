package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the YAML in definitions and {@code config.yaml} means once bound: block scalars, quoting,
 * null against absent, booleans, line endings, and the anchors and merge keys the jjq parser
 * resolves. Everything here but the anchor tests held with Jackson as well, so a parser change
 * that moves any of it changes what users' files mean.
 */
class YamlSemanticsTest {

    private static final String HEAD = "name: tpl-x\nparent: tpl-minimal\n";

    @TempDir
    Path home;
    private String savedHome;

    @BeforeEach
    void pointHomeAtATempDir() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", savedHome);
    }

    private static ImageDef image(String body) throws Exception {
        return ImageDef.parseYaml(HEAD + body);
    }

    private static ToolDef tool(String body) throws Exception {
        return ToolDef.loadFromStream(new ByteArrayInputStream(("name: x\n" + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void blockScalarsKeepTheirChompingAndFolding() throws Exception {
        assertEquals("one\ntwo\n", image("description: |\n  one\n  two\n").getDescription());
        assertEquals("one\ntwo", image("description: |-\n  one\n  two\n").getDescription());
        assertEquals("one\n\n\n", image("description: |+\n  one\n\n\nworkdir: /w\n").getDescription());
        assertEquals("folded one folded two\npara\n", image("description: >\n  folded one\n  folded two\n\n  para\n").getDescription());
        assertEquals("plain one plain two", image("description: plain one\n  plain two\n").getDescription());
    }

    @Test
    void aToolFileKeepsItsContentByteForByte() throws Exception {
        var file = tool("files:\n  - path: /etc/x\n    content: |\n      line1\n        indented\n      line3\n").getFiles().getFirst();
        assertEquals("line1\n  indented\nline3\n", file.getContent());
        var run = tool("run:\n  - |\n    set -e\n    echo \"$HOME\"\n").getRun();
        assertEquals(List.of("set -e\necho \"$HOME\"\n"), run);
    }

    @Test
    void quotedScalarsKeepTheirEscapes() throws Exception {
        assertEquals("tab\there é A \\ \"q\"", image("description: \"tab\\there \\u00e9 \\x41 \\\\ \\\"q\\\"\"\n").getDescription());
        assertEquals("it's \\n raw", image("description: 'it''s \\n raw'\n").getDescription());
        assertEquals("kept   ", image("workdir: \"kept   \"\n").getWorkdir());
        assertEquals("spaced", image("description: spaced   \n").getDescription());
        assertEquals("a#b c", image("description: a#b c # d\n").getDescription());
    }

    @Test
    void quotedNumbersAndWordsStayStrings() throws Exception {
        var env = image("env:\n  - name: A\n    value: \"0022\"\n  - name: B\n    value: \"3.10\"\n  - name: C\n    value: \"yes\"\n").getEnv();
        assertEquals(List.of("0022", "3.10", "yes"), env.stream().map(EnvEntry::getValue).toList());
        assertEquals(List.of("007", "on"), image("packages: [\"007\", \"on\"]\n").getPackages());
    }

    @Test
    void anExplicitNullReplacesTheDefaultAndAbsenceKeepsIt() throws Exception {
        assertEquals("", image("").getDescription());
        for (var body : List.of("description: ~\n", "description:\n", "description: null\n")) {
            assertNull(image(body).getDescription(), body);
        }
        for (var body : List.of("", "workdir: ~\n", "workdir:\n", "workdir: NULL\n")) {
            assertNull(image(body).getWorkdir(), body);
        }
    }

    @Test
    void yamlBooleansBindToBooleanFields() throws Exception {
        assertTrue(image("gui: yes\n").isGui());
        assertTrue(image("gui: on\n").isGui());
        assertTrue(image("gui: True\n").isGui());
        assertFalse(image("gui: no\n").isGui());
        assertFalse(image("gui: off\n").isGui());
        assertTrue(tool("verify: id\nverify_as_root: yes\n").isVerifyAsRoot());
    }

    @Test
    void lineEndingsByteOrderMarkAndDocumentMarkersDoNotChangeTheContent() throws Exception {
        var crlf = ImageDef.parseYaml((HEAD + "description: crlf\npackages:\n  - a\n").replace("\n", "\r\n"));
        assertEquals("crlf", crlf.getDescription());
        assertEquals(List.of("a"), crlf.getPackages());
        assertEquals("bom", ImageDef.parseYaml("\ufeff" + HEAD + "description: bom\n").getDescription());
        assertEquals("x", ImageDef.parseYaml("---\n" + HEAD + "description: x\n...\n").getDescription());
        assertEquals("first", image("description: first\n---\nname: tpl-y\ndescription: second\n").getDescription());
    }

    @Test
    void unicodeAndCommentsSurvive() throws Exception {
        var def = image("# c\ndescription: \"café 😀 日本\" # trailing\npackages: # x\n  - ünï # y\n");
        assertEquals("café 😀 日本", def.getDescription());
        assertEquals(List.of("ünï"), def.getPackages());
    }

    @Test
    void mapsAndListsKeepTheirOrder() throws Exception {
        var def = image("image_sha256:\n  zeta: \"1\"\n  alpha: \"2\"\n  mid: \"3\"\n"
                + "env:\n  - name: Z\n    value: \"1\"\n  - name: A\n    value: \"2\"\n");
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(def.getImageSha256().keySet()));
        assertEquals(List.of("Z", "A"), def.getEnv().stream().map(EnvEntry::getName).toList());
        var params = tool("parameters:\n  zeta:\n    default: \"1\"\n  alpha:\n    default: \"2\"\n").getParameters();
        assertEquals(List.of("zeta", "alpha"), List.copyOf(params.keySet()));
    }

    // Jackson rejected these: an alias read as the plain string "pk", and "<<" bound as a key.

    @Test
    void anAliasRepeatsTheAnchoredList() throws Exception {
        var def = ImageDef.parseYaml("common: &pk\n  - vim\n  - git\n" + HEAD + "packages: *pk\nremove_packages: *pk\n");
        assertEquals(List.of("vim", "git"), def.getPackages());
        assertEquals(List.of("vim", "git"), def.getRemovePackages());
    }

    @Test
    void aMergeKeyFillsInTheAnchoredMapping() throws Exception {
        var env = image("base: &b\n  name: X\n  value: \"1\"\nenv:\n  - <<: *b\n    value: \"2\"\n").getEnv().getFirst();
        assertEquals("X", env.getName());
        assertEquals("2", env.getValue(), "a key next to the merge wins over the merged one");
    }

    /** Saving writes back what was read: strings a plain scalar would turn into something else stay strings. */
    @Test
    void savingTheConfigKeepsEveryQuotedString() throws Exception {
        var awkward = List.of("yes", "no", "on", "0123", "1e3", "null", "~", "*alias", "&anchor", "- dash",
                "a: b", "#hash", "x #y", " lead", "trail ", "", "multi\nline\n", "tab\there", "café😀",
                "!tag", "@at", "`bt", "%pct", "{brace", "[brack", "'sq", "\"dq", "3.10", "true", "0x1F",
                ":colon", "?q", "|pipe", ">gt", "a\\b", "---", "...", "08", "1_000", "2026-10-11", "\r\n");
        var yaml = new StringBuilder("repo-paths:\n");
        Map<String, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < awkward.size(); i++) {
            var key = "k%02d".formatted(i);
            expected.put(key, awkward.get(i));
            yaml.append("  ").append(key).append(": \"").append(escape(awkward.get(i))).append("\"\n");
        }
        var file = home.resolve(".config/incus-spawn/config.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);

        assertEquals(expected, SpawnConfig.load().getRepoPaths());
        SpawnConfig.load().save();
        assertEquals(expected, SpawnConfig.load().getRepoPaths());
        var once = Files.readString(file);
        SpawnConfig.load().save();
        assertEquals(once, Files.readString(file), "a second save changes nothing");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
