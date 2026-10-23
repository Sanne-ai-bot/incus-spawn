package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.YamlMapper;
import io.hyperfoil.tools.jjq.yaml.YamlOptions;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shared jjq entry points for YAML definitions (Phase 1 of
 * incus-spawn-jjq-migration).
 *
 * <p>One process-wide {@link JqMapper} (thread-safe, with the generated
 * mappings registered once, so it is not built per parse). All
 * methods take {@link YamlOptions#STRICT} (duplicate-key detection, matching
 * the old Jackson {@code STRICT_DUPLICATE_DETECTION}) and translate jjq's
 * unchecked failures to {@link IOException}, preserving every caller's
 * contract (warnings, {@code parseFailed}, {@code throws} signatures).
 * {@link YamlErrors#friendly} unwraps the cause again for rendering.
 */
public final class Jjq {

    /**
     * Explicitly wired mapper: generated mappings pre-registered. No Jackson
     * bridge (main is annotation-free since the removal slices) and no
     * {@code ServiceLoader} scan or {@code Class.forName()} discovery at
     * runtime — the form native-image needs (see Phase 4).
     * Behavior-identical to {@code JqMapper.create()} on the JVM; the suites
     * pin that.
     */
    private static final JqMapper MAPPER = buildMapper();

    /**
     * Checked-exception databind over {@link #mapper()} with process-wide
     * {@link YamlOptions#STRICT}. Replaces eight hand-rolled overloads that
     * each repeated the same try/catch (jjq#117); cause chains are preserved
     * for {@link YamlErrors#friendly}.
     */
    private static final YamlMapper YAML = new YamlMapper(MAPPER, YamlOptions.STRICT);

    /**
     * A DTO whose generated mapping is not registered here binds reflectively, which the native
     * image cannot do; {@code JjqGeneratedMappingTest} reads what this registers to catch that.
     */
    static JqMapper buildMapper() {
        var builder = JqMapper.builder();
        dev.incusspawn.config.JqMappingRegistry.registerAll(builder);
        dev.incusspawn.tool.JqMappingRegistry.registerAll(builder);
        return builder.build();
    }

    private Jjq() {}

    public static JqMapper mapper() {
        return MAPPER;
    }

    public static ImageDef imageDef(String yaml) throws IOException {
        return YAML.readValue(yaml, ImageDef.class);
    }

    public static ImageDef imageDef(InputStream in) throws IOException {
        return YAML.readValue(in, ImageDef.class);
    }

    public static ImageDef imageDef(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, ImageDef.class);
    }

    /** {@code base-image-pin.yaml}; null for an empty document. */
    public static BaseImagePin baseImagePin(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, BaseImagePin.class);
    }

    public static ToolDef toolDef(String yaml) throws IOException {
        return YAML.readValue(yaml, ToolDef.class);
    }

    public static ToolDef toolDef(InputStream in) throws IOException {
        return YAML.readValue(in, ToolDef.class);
    }

    public static ProjectConfig projectConfig(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, ProjectConfig.class);
    }

    public static SpawnConfig spawnConfig(String yaml) throws IOException {
        return YAML.readValue(yaml, SpawnConfig.class);
    }

    public static SpawnConfig spawnConfig(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, SpawnConfig.class);
    }

    /** {@code mcp.yaml}; null for an empty document. */
    public static McpConfig mcpConfig(String yaml) throws IOException {
        return YAML.readValue(yaml, McpConfig.class);
    }

    /** Serialize a config for {@code save()}: block-style YAML, same shape Jackson wrote. */
    public static String toYaml(Object config) {
        return YAML.writeValueAsString(config);
    }

    /** Write a config file straight from the tree: no intermediate String. */
    public static void writeYaml(java.nio.file.Path file, Object config) throws IOException {
        YAML.writeValue(file, config);
    }

    /**
     * A scalar as written in the file: {@code 0022}, {@code 3.10} and {@code yes} stay as typed
     * (jjq's {@code asText()} renders the resolved number or boolean, Hyperfoil/jjq#126). For the
     * converters that read the tree themselves; binding to a {@code String} field already does this.
     */
    public static String text(JqValue value) {
        var written = value.sourceText();
        return written != null ? written : value.asText();
    }

    /**
     * Why a parse failed, for one line of output: jjq's strict parse quotes the whole document
     * after the reason ({@code ... (while parsing '...')}), which can be megabytes.
     */
    public static String brief(Throwable e) {
        var message = e.getMessage();
        if (message == null) return "";
        var first = message.lines().findFirst().orElse("");
        var quoted = first.indexOf(" (while parsing '");
        if (quoted >= 0) first = first.substring(0, quoted);
        return first.length() > 200 ? first.substring(0, 200) + "..." : first;
    }

    // ---- JSON reads (Incus API responses) ----
    //
    // Jackson parity notes: daemon responses are machine-generated and never carry
    // explicit nulls (Incus omits rather than nulls), so unlike
    // {@link AccountResolver#navigate} — which distinguishes missing ("") from
    // explicit null ("null") because user files can mean both — these treat a null
    // like a missing node. Containers read as "" (Jackson's asText on a container).

    private static final java.math.BigDecimal INT_MIN = java.math.BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final java.math.BigDecimal INT_MAX = java.math.BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final java.math.BigDecimal LONG_MIN = java.math.BigDecimal.valueOf(Long.MIN_VALUE);
    private static final java.math.BigDecimal LONG_MAX = java.math.BigDecimal.valueOf(Long.MAX_VALUE);

    /**
     * A whole number that fits an int, or null: jjq's {@code intValue()}/{@code asInt()} wrap
     * beyond the range and truncate a fraction. Bounds are compared first, so a huge literal is
     * refused before its digits are looked at.
     */
    public static Integer exactInt(java.math.BigDecimal number) {
        if (number == null || number.compareTo(INT_MIN) < 0 || number.compareTo(INT_MAX) > 0) return null;
        try {
            return number.intValueExact();
        } catch (ArithmeticException fractional) {
            return null;
        }
    }

    /** {@link #exactInt} of a JSON or YAML number; null for anything else. */
    public static Integer exactInt(JqValue value) {
        var number = literalNumber(value);
        return number == null ? null : exactInt(number);
    }

    /**
     * The value of a number, or null when it is not a number or cannot be trusted: the YAML
     * parser reads {@code .inf} and {@code .nan} as numbers whose value is 0, so a number whose
     * source text is not a finite decimal equal to its value is refused. JSON numbers carry no
     * source text and keep their exact value.
     */
    private static java.math.BigDecimal literalNumber(JqValue value) {
        if (value == null || !value.isNumber()) return null;
        var number = value.decimalValue();
        var written = value.sourceText();
        if (written == null) return number;
        try {
            return new java.math.BigDecimal(written.strip()).compareTo(number) == 0 ? number : null;
        } catch (NumberFormatException notFinite) {
            return null;
        }
    }

    /** As {@link #exactInt}, for a long. */
    public static Long exactLong(JqValue value) {
        var number = literalNumber(value);
        if (number == null || number.compareTo(LONG_MIN) < 0 || number.compareTo(LONG_MAX) > 0) return null;
        try {
            return number.longValueExact();
        } catch (ArithmeticException fractional) {
            return null;
        }
    }

    /** Jackson's {@code asLong(dflt)}. */
    public static long longOr(JqValue value, long dflt) {
        var exact = exactLong(value); // longValue() would wrap beyond the long range
        return exact != null ? exact : dflt;
    }

    /** Jackson's {@code isMissingNode()}: absent (or, for responses, null). */
    public static boolean isMissing(JqValue value) {
        return value == null || value.isNull();
    }
}
