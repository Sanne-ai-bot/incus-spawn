package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.JsonNode;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Test-only bridge from {@link JqValue} to Jackson, for the networknt schema
 * validator in {@code StructuredResults}, the last test code that speaks Jackson.
 */
public final class JacksonTrees {

    private JacksonTrees() {}

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static JsonNode toJsonNode(JqValue value) {
        if (value == null || value.isNull()) return JSON.nullNode();
        if (value.isString()) return JSON.getNodeFactory().textNode(value.asText());
        if (value.isIntegralNumber()) return JSON.getNodeFactory().numberNode(value.longValue());
        if (value.isFloatingPointNumber()) return JSON.getNodeFactory().numberNode(value.doubleValue());
        if (value.isBoolean()) return JSON.getNodeFactory().booleanNode(value.booleanValue());
        if (value.isArray()) {
            var arr = JSON.createArrayNode();
            value.asList().forEach(child -> arr.add(toJsonNode(child)));
            return arr;
        }
        if (value.isObject()) {
            var obj = JSON.createObjectNode();
            value.asMap().forEach((k, v) -> obj.set(k, toJsonNode(v)));
            return obj;
        }
        return JSON.getNodeFactory().textNode(value.asText());
    }
}
