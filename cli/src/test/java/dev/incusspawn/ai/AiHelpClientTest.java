package dev.incusspawn.ai;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiHelpClientTest {

    /** The provider of the account asked by default, or null when none can answer. */
    private static AiHelpClient.Provider provider(SpawnConfig config) {
        var targets = AiHelpClient.targets(config);
        return targets.isEmpty() ? null : targets.getFirst().provider();
    }

    private static SpawnConfig config() {
        return new SpawnConfig();
    }

    @Test
    void oauthTokenAloneIsNotAUsableProvider() {
        // A Pro/Max token is only valid for Claude Code itself; the Messages API answers
        // anything else with an opaque HTTP 429, so it must never be selected here.
        var config = config();
        config.getClaude().setOauthToken(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        assertTrue(config.getClaude().isOauthMode());
        assertNull(provider(config));
    }

    @Test
    void oauthTokenDoesNotShadowAnotherProvider() {
        var config = config();
        config.getClaude().setOauthToken(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        config.getOpenai().setApiKey("sk-openai-test");
        assertEquals(AiHelpClient.Provider.OPENAI, provider(config));
    }

    @Test
    void apiKeySelectsAnthropic() {
        var config = config();
        config.getClaude().setApiKey("sk-ant-api03-test");
        assertEquals(AiHelpClient.Provider.ANTHROPIC, provider(config));
    }

    @Test
    void vertexSelectsVertex() {
        var config = config();
        config.getClaude().setUseVertex(true);
        // Region and project are what make the account complete(), and an incomplete one is
        // deliberately never selected -- see incompleteVertexSelectsNothing below.
        config.getClaude().setCloudMlRegion("europe-west1");
        config.getClaude().setVertexProjectId("acme");
        assertEquals(AiHelpClient.Provider.VERTEX, provider(config));
    }

    @Test
    void incompleteVertexSelectsNothing() {
        // 'useVertex: true' with no region or project cannot serve a call: the request URI
        // would be built with empty path segments. It stays configured so ProxyMain can
        // report the misconfiguration, but it must not be picked as a provider here.
        var config = config();
        config.getClaude().setUseVertex(true);
        assertTrue(config.getClaude().isUseVertex());
        assertNull(provider(config));
    }

    @Test
    void noCredentialsSelectsNothing() {
        assertNull(provider(config()));
    }

    @Test
    void oauthOnlyConfigIsNotReportedAsMissingCredentials() {
        var config = config();
        config.getClaude().setOauthToken(SpawnConfig.ClaudeConfig.PLACEHOLDER_OAUTH_TOKEN);
        var message = AiHelpClient.noProvider(config).message();
        assertTrue(message.contains("Claude Code"), message);
        assertTrue(message.contains("Max and Team plans include monthly API credits"), message);
        assertTrue(message.contains(AiHelpClient.API_CREDITS_URL), message);
        assertFalse(message.startsWith("No AI credentials"), message);
    }

    @Test
    void emptyConfigIsReportedAsMissingCredentials() {
        assertTrue(AiHelpClient.noProvider(config()).message().startsWith("No AI credentials"));
    }

    @Test
    void targetsListEveryUsableAccountWithTheDefaultFirst() {
        var config = config();
        var claude = config.getClaude();
        claude.putAccount("personal", SpawnConfig.ClaudeAccount.ofOauth("sk-ant-oat01-abc"));
        claude.putAccount("console", SpawnConfig.ClaudeAccount.ofApiKey("sk-ant-api03-xyz"));
        claude.putAccount("gcp", SpawnConfig.ClaudeAccount.ofVertex("europe-west1", "acme"));
        claude.setDefaultAccount("gcp");
        config.getOpenai().setApiKey("sk-openai-test");

        var targets = AiHelpClient.targets(config);

        assertEquals(List.of(
                new AiHelpClient.Target(AiHelpClient.Provider.VERTEX, "gcp", AiHelpClient.VERTEX_MODEL),
                new AiHelpClient.Target(AiHelpClient.Provider.ANTHROPIC, "console", AiHelpClient.ANTHROPIC_MODEL),
                new AiHelpClient.Target(AiHelpClient.Provider.OPENAI, "", AiHelpClient.OPENAI_MODEL)),
                targets);
        assertEquals(AiHelpClient.Provider.VERTEX, provider(config));
        assertEquals(List.of("personal"), AiHelpClient.subscriptionAccounts(config));
    }

    @Test
    void bothClaudeTargetsAskSonnet55() {
        // The Claude API and Vertex name the model alike from 5.5 on, but have diverged
        // before, so each pin is checked on its own (#1675).
        assertEquals("claude-sonnet-5-5", AiHelpClient.ANTHROPIC_MODEL);
        assertEquals("claude-sonnet-5-5", AiHelpClient.VERTEX_MODEL);
    }

    @Test
    void labelsNameTheAccountUnlessItIsTheLegacyOne() {
        var named = new AiHelpClient.Target(AiHelpClient.Provider.VERTEX, "redhat", AiHelpClient.VERTEX_MODEL);
        assertEquals("redhat (Vertex AI)", named.label());

        var config = config();
        config.getClaude().setApiKey("sk-ant-api03-test");
        assertEquals("Anthropic API key", AiHelpClient.targets(config).getFirst().label());
    }

    @Test
    void everySystemBlockEndsInACacheBreakpoint() {
        var system = AiHelpClient.cachedSystem(List.of("docs", "definitions"));
        assertEquals(2, system.size());
        for (int i = 0; i < system.size(); i++) {
            assertEquals("text", system.get(i).path("type").asText());
            assertEquals("ephemeral", system.get(i).path("cache_control").path("type").asText());
            // No explicit TTL: the 5-minute default suits help traffic (see cachedSystem).
            assertTrue(system.get(i).path("cache_control").path("ttl").isMissingNode());
        }
        assertEquals("docs", system.get(0).path("text").asText());
    }

    /** Sonnet 5.5 answers at effort low, on the Claude API and Vertex alike (#1675). */
    @Test
    void claudeIsAskedAtEffortLow() {
        var anthropic = AiHelpClient.messagesBody("model", AiHelpClient.ANTHROPIC_MODEL, "q", List.of("docs"));
        var vertex = AiHelpClient.messagesBody("anthropic_version", AiHelpClient.VERTEX_VERSION, "q", List.of("docs"));
        assertEquals("claude-sonnet-5-5", anthropic.pathText("model", null));
        assertEquals("vertex-2023-10-16", vertex.pathText("anthropic_version", null));
        for (var body : List.of(anthropic, vertex)) {
            assertEquals("low", body.getField("output_config").pathText("effort", null));
            assertEquals(4096L, Jjq.longOr(body.getField("max_tokens"), 0));
            assertEquals("true", body.getField("stream").toJsonString());
            assertEquals("docs", body.getField("system").getElement(0).pathText("text", null));
            assertEquals("q", body.getField("messages").getElement(0).pathText("content", null));
        }
    }

    @Test
    void messagesStreamReportsTextAndCacheUsage() throws Exception {
        var stream = sse("""
                {"type":"message_start","message":{"usage":{"input_tokens":12,"cache_read_input_tokens":48000,"cache_creation_input_tokens":3100,"output_tokens":1}}}
                {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}
                {"type":"content_block_stop","index":0}
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":312}}
                {"type":"message_stop"}
                """);
        var text = new StringBuilder();
        var end = AiHelpClient.processStream(stream, text::append);
        assertEquals("Hello there", text.toString());
        assertNull(end.note());
        var usage = end.usage();
        assertEquals(new AiHelpClient.Usage(12, 48000, 3100, 312), usage);
        assertEquals(51112, usage.totalInputTokens());
    }

    @Test
    void messagesStreamSaysWhyAnAnswerEndedEarly() throws Exception {
        // A refusal or the length limit ends the stream like a finished answer; without a
        // word the reader sees an empty or cut-off answer and cannot tell why. The word is
        // isx's, not the model's, so it comes back beside the answer, never inside it.
        var refused = new StringBuilder();
        var refusal = AiHelpClient.processStream(sse("""
                {"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}
                {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""}}
                {"type":"content_block_stop","index":0}
                {"type":"message_delta","delta":{"stop_reason":"refusal"},"usage":{"output_tokens":3}}
                {"type":"message_stop"}
                """), refused::append);
        assertEquals("", refused.toString());
        assertEquals(AiHelpClient.REFUSAL_NOTE, refusal.note());
        assertEquals(new AiHelpClient.Usage(12, 0, 0, 3), refusal.usage());

        // The context window can end an answer too; the API asks to treat it like max_tokens.
        for (var reason : List.of("max_tokens", "model_context_window_exceeded")) {
            var truncated = new StringBuilder();
            var cutOff = AiHelpClient.processStream(sse("""
                    {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                    {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Run isx"}}
                    {"type":"content_block_stop","index":0}
                    {"type":"message_delta","delta":{"stop_reason":"%s"},"usage":{"output_tokens":4096}}
                    {"type":"message_stop"}
                    """.formatted(reason)), truncated::append);
            assertEquals("Run isx", truncated.toString(), reason);
            assertEquals(AiHelpClient.TRUNCATED_NOTE, cutOff.note(), reason);
        }
    }

    @Test
    void openAiStreamReportsCachedTokens() throws Exception {
        var stream = sse("""
                {"choices":[{"delta":{"content":"Hi"}}],"usage":null}
                {"choices":[],"usage":{"prompt_tokens":50000,"completion_tokens":200,"prompt_tokens_details":{"cached_tokens":49000}}}
                """);
        var text = new StringBuilder();
        var usage = AiHelpClient.processOpenAiStream(stream, text::append).usage();
        assertEquals("Hi", text.toString());
        assertEquals(new AiHelpClient.Usage(1000, 49000, 0, 200), usage);
    }

    /** The stream's bytes are parsed as they came, so invalid UTF-8 is refused rather than shown as U+FFFD. */
    @Test
    void streamEventWithInvalidUtf8IsRefusedSayingWhere() {
        var line = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"a\u00ffb\"}}\n\n";
        var stream = new java.io.ByteArrayInputStream(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        var text = new StringBuilder();
        var e = assertThrows(IllegalArgumentException.class, () -> AiHelpClient.processOpenAiStream(stream, text::append));
        assertEquals("the AI provider's stream event is not valid JSON: Invalid UTF-8 at line 1, column 49", e.getMessage());
        assertEquals("", text.toString());
    }

    /** Trimming the payload stops at a UTF-8 byte: invalid UTF-8 after the JSON is refused, not dropped. */
    @Test
    void invalidUtf8AfterAStreamEventIsRefusedNotTrimmed() {
        var line = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\u00ff\n\n";
        var stream = new java.io.ByteArrayInputStream(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        var text = new StringBuilder();
        assertThrows(IllegalArgumentException.class, () -> AiHelpClient.processOpenAiStream(stream, text::append));
        assertEquals("", text.toString());
    }

    private static java.io.InputStream sse(String jsonLines) {
        var sb = new StringBuilder();
        jsonLines.lines().forEach(l -> sb.append("data: ").append(l).append("\n\n"));
        sb.append("data: [DONE]\n\n");
        return new java.io.ByteArrayInputStream(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void providerErrorCannotDriveTheTerminal() {
        // The error is shown by isx ask and in AI Help; the provider wrote its body.
        assertEquals("API error (401, auth  x, req [2J): bad  key ]0;t ",
                AiHelpClient.describeError(401, """
                        {"error":{"type":"auth\\u202E\\u009bx","message":"bad\\r\\nkey\\u001b]0;t\\u0007"},\
                        "request_id":"req\\u001b[2J"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("API error (502): <html>  </html>", AiHelpClient.describeError(502,
                "<html>\u0085\n</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void streamErrorCannotDriveTheTerminal() {
        var stream = sse("""
                {"type":"error","error":{"message":"overloaded\\u001b[2J"}}
                """);
        var e = assertThrows(java.io.IOException.class, () -> AiHelpClient.processStream(stream, s -> {}));
        assertEquals("API stream error: overloaded [2J", e.getMessage());
    }

    /** An error body with invalid UTF-8 is not JSON: it is shown raw, not read as an error message. */
    @Test
    void errorBodyWithInvalidUtf8IsShownRaw() {
        var body = "{\"error\":{\"message\":\"a\u00ffb\"}}".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals("API error (500): {\"error\":{\"message\":\"a\ufffdb\"}}", AiHelpClient.describeError(500, body));
    }

    @Test
    void longErrorBodyIsCutBetweenCharacters() {
        // 199 letters, then an emoji whose two UTF-16 units straddle the cut.
        var body = "x".repeat(199) + "\uD83D\uDE00" + "tail";
        assertEquals("API error (502): " + "x".repeat(199) + "\uD83D\uDE00...", AiHelpClient.describeError(502,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
