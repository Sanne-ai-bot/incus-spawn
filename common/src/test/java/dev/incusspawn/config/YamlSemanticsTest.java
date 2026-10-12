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

    private static String friendly(java.io.IOException e) {
        return YamlErrors.friendly("x.yaml", e);
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

    // Fixed in jjq 0.2.2 (Hyperfoil/jjq#126, #128); these held with Jackson and were lost with 0.2.1.

    @Test
    void anUnquotedScalarBoundToAStringKeepsItsText() throws Exception {
        var env = image("env:\n  - name: A\n    value: 0022\n  - name: B\n    value: 3.10\n"
                + "  - name: C\n    value: yes\n  - name: D\n    value: 0x1F\n").getEnv();
        assertEquals(List.of("0022", "3.10", "yes", "0x1F"), env.stream().map(EnvEntry::getValue).toList());
    }

    @Test
    void unquotedToolParametersAndSkillsKeepTheirText() throws Exception {
        var def = image("tools:\n  - mvnd:\n      version: 3.10\n      umask: 0022\nskills:\n  - 0123\n");
        var params = def.getTools().getFirst().getParams();
        assertEquals("3.10", params.get("version"));
        assertEquals("0022", params.get("umask"));
        assertEquals(List.of("0123"), def.getSkills().getList());
    }

    @Test
    void aTypeErrorNamesTheLineAndTheKeyAsWritten() throws Exception {
        var shape = assertThrows(java.io.IOException.class, () -> image("# c\npackages: vim\n"));
        assertTrue(friendly(shape).startsWith("x.yaml:4: 'packages' expects a list"), friendly(shape));
        var bool = assertThrows(java.io.IOException.class, () -> image("gui: maybe\n"));
        assertTrue(friendly(bool).startsWith("x.yaml:3: "), friendly(bool));
        assertTrue(friendly(bool).contains("'gui' expects true or false"), friendly(bool));
        var top = assertThrows(java.io.IOException.class, () -> ImageDef.parseYaml("- a\n"));
        assertEquals("x.yaml:1: the file must be a mapping of settings, but is a list", friendly(top));
        var commented = assertThrows(java.io.IOException.class, () -> ImageDef.parseYaml("# note\n- a\n"));
        assertEquals("x.yaml:2: the file must be a mapping of settings, but is a list", friendly(commented));
        var hyphen = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("auto-clone-repos:\n  - a\n"));
        var message = YamlErrors.friendly("config.yaml", hyphen);
        assertTrue(message.contains("'auto-clone-repos' expects a single value"), message);
        assertFalse(message.contains("$"), message);
    }

    @Test
    void aScalarWhereAMappingBelongsIsRejected() {
        assertThrows(java.io.IOException.class, () -> tool("proxy: x\n"));
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

    // An integer setting that is not an integer fails the file, as with Jackson, with the field as
    // written. jjq 0.2.3 refuses most of these itself (Hyperfoil/jjq#133), but binds .inf and .nan
    // as 0: a grace of 0 reaps orphans at once.

    @Test
    void aWordForAnIntegerSettingFailsWithTheFieldAndLine() {
        var e = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("mcp:\n  orphan-grace-hours: abc\n"));
        var message = YamlErrors.friendly("config.yaml", e);
        assertEquals("config.yaml:2: 'mcp.orphan-grace-hours' expects a whole number, but got 'abc'", message);
    }

    @Test
    void aBooleanForAnIntegerSettingFails() {
        var e = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("mcp:\n  orphan-grace-hours: true\n"));
        var message = YamlErrors.friendly("config.yaml", e);
        assertTrue(message.startsWith("config.yaml:2: ") && message.contains("orphan-grace-hours"), message);
    }

    @Test
    void aQuotedNumberForAnIntegerSettingIsThatNumber() throws Exception {
        assertEquals(24, Jjq.spawnConfig("mcp:\n  orphan-grace-hours: \"24\"\n").mcp().orphanGraceHours());
        assertEquals(7, Jjq.spawnConfig("mcp:\n  max-instances: 7\n").mcp().maxInstances());
    }

    @Test
    void aBlankIntegerSettingIsUnset() throws Exception {
        assertEquals(McpConfig.DEFAULT_MAX_INSTANCES, Jjq.spawnConfig("mcp:\n  max-instances: \"\"\n").mcp().maxInstances());
    }

    /** .inf and .nan read as 0 in jjq, and 1e400 is out of range: "never" must not be an immediate reap. */
    @Test
    void anInfiniteNanOrHugeNumberFailsRatherThanBeingZero() {
        for (var text : java.util.List.of(".inf", "-.inf", ".nan", "1e400", "-1e400")) {
            for (var key : java.util.List.of("orphan-grace-hours", "max-instances")) {
                var e = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("mcp:\n  " + key + ": " + text + "\n"),
                        key + ": " + text);
                assertTrue(friendly(e).startsWith("x.yaml:2: 'mcp." + key + "' expects a whole number"), friendly(e));
            }
        }
    }

    /**
     * Pins why Jjq.literalNumber checks the source text, so the day jjq fixes it this fails and the comments can
     * say so; the check stays regardless, since it also refuses 0x10 and 1_000.
     */
    @Test
    void jjqStillReadsInfinityAndNanAsZero() {
        for (var text : java.util.List.of(".inf", "-.inf", ".nan")) {
            var value = io.hyperfoil.tools.jjq.yaml.JqYaml.parse("a: " + text + "\n").getField("a");
            assertTrue(value.isNumber(), text);
            assertEquals(0, value.decimalValue().signum(), text);
        }
    }

    @Test
    void aListOrMappingForAnIntegerSettingSaysWhatItWas() {
        var list = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("mcp:\n  max-instances: [8]\n"));
        assertTrue(friendly(list).endsWith("expects a whole number, but got a list"), friendly(list));
        var mapping = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("mcp:\n  max-instances:\n    n: 8\n"));
        assertTrue(friendly(mapping).endsWith("expects a whole number, but got a mapping"), friendly(mapping));
    }

    @Test
    void aKeyWithADotIsQuotedWhereItIsNamed() {
        var e = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig(
                "mcp:\n  delegate-permission-modes:\n    tpl.v2: [plan]\n"));
        assertTrue(friendly(e).contains("'mcp.delegate-permission-modes[\"tpl.v2\"]' expects a single value"), friendly(e));
        assertTrue(friendly(e).contains("e.g. 'tpl.v2: value'"), friendly(e));
    }

    @Test
    void aCountBeyondTheLongRangeIsTheDefaultNotAWrappedValue() {
        assertEquals(7, Jjq.longOr(io.hyperfoil.tools.jjq.value.JqValues.parseStrict("7"), -1));
        assertEquals(-1, Jjq.longOr(io.hyperfoil.tools.jjq.value.JqValues.parseStrict("99999999999999999999"), -1));
        assertEquals(-1, Jjq.longOr(io.hyperfoil.tools.jjq.value.JqValues.parseStrict("\"7\""), -1));
    }

    /** Since jjq 0.2.3 (Hyperfoil/jjq#132): a repeated key is refused, not kept with the first winning on lookup. */
    @Test
    void aJsonDocumentWithARepeatedKeyIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> io.hyperfoil.tools.jjq.value.JqValues.parseStrict("{\"a\":1,\"a\":2}"));
    }

    @Test
    void aLabelNeverFailsOnASegmentThatIsNotAKeyOrIndex() {
        assertEquals("a[\"b.c\"][2]", SecretRedactor.label(java.util.List.of("a", "b.c", 2)));
        assertEquals("a.7", SecretRedactor.label(java.util.List.of("a", 7L)));
    }

    @Test
    void aStringIsAnIntegerOnlyIfItIsOneLiterally() throws Exception {
        for (var text : java.util.List.of("1e2", "5.0", "0x10", "abc", "9999999999")) {
            assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("mcp:\n  max-instances: \"" + text + "\"\n"), text);
        }
        assertEquals(24, Jjq.spawnConfig("mcp:\n  max-instances: \" 24 \"\n").mcp().maxInstances());
    }

    @Test
    void anIntegerSettingBeyondTheIntRangeFailsRatherThanWrapping() {
        var e = assertThrows(java.io.IOException.class, () -> Jjq.spawnConfig("mcp:\n  max-instances: 99999999999\n"));
        var message = YamlErrors.friendly("config.yaml", e);
        assertTrue(message.startsWith("config.yaml:2: ") && message.contains("max-instances"), message);
    }

    @Test
    void aToolParametersBoundsAreIntegersToo() throws Exception {
        assertThrows(java.io.IOException.class, () -> tool("parameters:\n  n:\n    type: integer\n    min: abc\n"));
        assertThrows(java.io.IOException.class, () -> tool("parameters:\n  n:\n    type: integer\n    max: 99999999999\n"));
        assertEquals(3, tool("parameters:\n  n:\n    type: integer\n    min: 3\n").getParameters().get("n").getMin());
    }

    // Fixed in jjq 0.2.3 (Hyperfoil/jjq#134): with 0.2.2 an empty value took the keys after it as its value.

    @Test
    void anEmptyEnvValueBeforeOtherKeysIsEmptyAndKeepsThem() throws Exception {
        var env = image("env:\n  - name: FOO\n    value:\n    strategy: append\n    separator: \":\"\n").getEnv().getFirst();
        assertEquals("", env.getValue());
        assertEquals(EnvEntry.Strategy.APPEND, env.getStrategy());
        assertEquals(":", env.getSeparator());
    }

    /** An empty separator before the strategy must not drop the strategy: PATH prepended, not set. */
    @Test
    void anEmptyEnvSeparatorBeforeTheStrategyKeepsTheStrategy() throws Exception {
        var env = image("env:\n  - name: PATH\n    value: /x\n    separator:\n    strategy: prepend\n").getEnv().getFirst();
        assertEquals(EnvEntry.Strategy.PREPEND, env.getStrategy());
        assertEquals(" ", env.getSeparator());
    }

    @Test
    void anEmptyEnvNameIsRefused() {
        var e = assertThrows(java.io.IOException.class, () -> image("env:\n  - name:\n    value: x\n"));
        assertTrue(friendly(e).contains("requires a 'name' field"), friendly(e));
    }

    @Test
    void anEnvFieldWrittenAsAMappingOrListIsRefused() {
        var mapping = assertThrows(java.io.IOException.class, () -> image("env:\n  - name: A\n    value:\n      k: v\n"));
        assertTrue(friendly(mapping).contains("Env entry field 'value' must be a single value"), friendly(mapping));
        assertFalse(friendly(mapping).contains("YAML parser"), "no hint about the parser bug jjq 0.2.3 fixed: " + friendly(mapping));
        var list = assertThrows(java.io.IOException.class, () -> image("env:\n  - name: [A]\n    value: x\n"));
        assertTrue(friendly(list).contains("Env entry field 'name' must be a single value"), friendly(list));
    }

    @Test
    void anEmptyEnvValueAsTheLastKeyIsEmpty() throws Exception {
        var env = image("env:\n  - name: FOO\n    strategy: append\n    value:\n").getEnv().getFirst();
        assertEquals("", env.getValue());
        assertEquals(EnvEntry.Strategy.APPEND, env.getStrategy());
    }

    @Test
    void anEnvNameIsTakenAsWritten() throws Exception {
        assertEquals("on", image("env:\n  - name: on\n    value: x\n").getEnv().getFirst().getName());
    }
}
