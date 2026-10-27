package dev.incusspawn.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How isx mcp writes its log for its client (#1703). */
class McpLogTest {

    /** A message and its details are one write, with the prefix on its first line alone. */
    @Test
    void aMessageAndItsDetailsAreOneBlock() {
        try (var log = McpLog.capture()) {
            McpLog.log("tool failed:", "java.lang.IllegalStateException\n\tat Foo.bar(Foo.java:1)");
            assertEquals(List.of("isx mcp: tool failed:\njava.lang.IllegalStateException\n\tat Foo.bar(Foo.java:1)"),
                    log.blocks(), "one write, so nothing can land inside it");
        }
    }
}
