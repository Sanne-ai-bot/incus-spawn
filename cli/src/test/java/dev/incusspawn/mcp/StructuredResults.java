package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks tool results the way a client would: a successful result's {@code structuredContent}
 * against the tool's {@code outputSchema} with a real JSON Schema 2020-12 validator, and an error
 * result for no {@code structuredContent} and a code in {@code _meta}. Remembers which tools
 * returned a checked success, so a test can tell that it covered them all.
 */
final class StructuredResults {

    private static final String DIALECT = SpecificationVersion.DRAFT_2020_12.getDialectId();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Schema METASCHEMA = REGISTRY.getSchema(SchemaLocation.of(DIALECT));
    static final Set<String> CODES = java.util.Arrays.stream(ToolError.Code.values())
            .map(ToolError.Code::wire).collect(Collectors.toUnmodifiableSet());

    private final Map<String, McpTool> tools;
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();
    final Set<String> succeeded = ConcurrentHashMap.newKeySet();

    StructuredResults(List<McpTool> tools) {
        this.tools = tools.stream().collect(Collectors.toMap(McpTool::name, Function.identity()));
    }

    Set<String> toolNames() {
        return tools.keySet();
    }

    /** {@code result} is the {@code result} of a {@code tools/call} of {@code tool}. */
    JqValue check(String tool, JqValue result) {
        if (result.path("isError").asBoolean(false)) {
            assertFalse(result instanceof JqObject o && o.keys().contains("structuredContent"),
                    tool + ": an error result must carry no structuredContent (1.x SDK clients validate it): " + result);
            var error = result.path("_meta").path(ToolResult.ERROR_META);
            assertTrue(CODES.contains(error.pathText("code", "")), tool + ": an error needs a known code: " + result);
            assertEquals(result.path("content").getElement(0).path("text").asText(), error.path("message").asText());
            return result;
        }
        var structured = result.path("structuredContent");
        assertFalse(structured.isMissingNode(), tool + " returned no structuredContent: " + result);
        assertTrue(structured.isObject(), tool + ": structuredContent must be an object");
        var errors = compiled.computeIfAbsent(tool, t -> {
            var schema = tools.get(t);
            assertNotNull(schema, "no tool " + t);
            return compile(t, schema.outputSchema());
        }).validate(structured.toJsonString(), InputFormat.JSON);
        assertTrue(errors.isEmpty(), tool + ": structuredContent does not match its outputSchema: " + errors
                + "\n" + structured.toJsonString());
        succeeded.add(tool);
        return result;
    }

    /**
     * A schema every client can compile: no {@code $schema}, or 2020-12 (clients dispatch on it,
     * and default to 2020-12), and valid against the 2020-12 metaschema -- a schema a client cannot
     * compile fails every call of its tool, not just a bad one.
     */
    static Schema compile(String tool, JqValue schema) {
        var dialect = schema.path("$schema");
        assertTrue(dialect.isMissingNode() || dialect.asText().equals(DIALECT),
                tool + ": declares the dialect " + dialect);
        var text = schema.toJsonString();
        var invalid = METASCHEMA.validate(text, InputFormat.JSON);
        assertTrue(invalid.isEmpty(), tool + ": not a valid 2020-12 schema: " + invalid);
        assertEquals("object", schema.path("type").asText(), tool + ": an outputSchema must describe an object");
        return REGISTRY.getSchema(text, InputFormat.JSON);
    }
}
