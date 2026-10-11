package dev.incusspawn.mcp;

import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** An integer argument is an integer literal that fits an int, as JSON-RPC clients send it. */
class McpToolArgsTest {

    private static McpTool.Args args(String json) {
        return new McpTool.Args(JqValues.parseStrict(json));
    }

    @Test
    void anIntegerInRangeIsTaken() {
        assertEquals(5, args("{\"n\":5}").integer("n"));
        assertEquals(Integer.MAX_VALUE, args("{\"n\":2147483647}").integer("n"));
        assertEquals(Integer.MIN_VALUE, args("{\"n\":-2147483648}").integer("n"));
        assertNull(args("{}").integer("n"));
    }

    @Test
    void anIntegerBeyondLongIsRefusedNotWrapped() {
        // 2^64 + 5 wrapped to 5 through longValue()
        assertThrows(ToolError.class, () -> args("{\"n\":18446744073709551621}").integer("n"));
        assertThrows(ToolError.class, () -> args("{\"n\":9223372036854775808}").integer("n"));
        assertThrows(ToolError.class, () -> args("{\"n\":2147483648}").integer("n"));
        assertThrows(ToolError.class, () -> args("{\"n\":1" + "0".repeat(200_000) + "}").integer("n"));
    }

    @Test
    void aStringOrAFractionIsNotAnInteger() {
        assertThrows(ToolError.class, () -> args("{\"n\":\"5\"}").integer("n"));
        assertThrows(ToolError.class, () -> args("{\"n\":5.5}").integer("n"));
        // 5.0 and 1e2 are taken as 5 and 100: jjq keeps no trace of how a JSON number was written.
    }
}
