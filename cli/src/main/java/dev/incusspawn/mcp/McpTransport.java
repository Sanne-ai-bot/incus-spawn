package dev.incusspawn.mcp;

import java.io.IOException;

/**
 * Where MCP messages come from and go to. Only stdio exists today; a loopback HTTP transport
 * would implement this too, identifying its session by token rather than by process.
 */
interface McpTransport {

    /**
     * The next message as the bytes the client sent, or null once it has gone away. Bytes, not
     * text: the server refuses invalid UTF-8, which decoding would hide as U+FFFD.
     */
    byte[] read() throws IOException;

    /** Send one message. Safe to call from several threads at once. */
    void send(String json) throws IOException;
}
