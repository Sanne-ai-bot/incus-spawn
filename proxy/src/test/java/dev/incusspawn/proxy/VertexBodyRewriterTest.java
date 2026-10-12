package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The streaming Vertex translation does what the tree translation it replaced did (#1315), checked
 * against that translation ({@link LegacyVertexTranslation}) for every body below, however the body
 * is split into chunks; and parts from it only where #1315 decided it should, each case named.
 */
class VertexBodyRewriterTest {

    static final String PATH = "/v1/messages";
    static final String PROJECT = "proj";
    static final String REGION = "us-east5";

    /** What the rewriter made of a body: its path and output, or the refusal it threw. */
    record Outcome(String path, byte[] body, Exception refusal) {
        static Outcome of(Exception e) {
            return new Outcome(null, null, e);
        }
    }

    static Outcome rewrite(byte[] body, int... splits) {
        var out = new ByteArrayOutputStream();
        var rewriter = new VertexBodyRewriter(PATH, PROJECT, REGION, b -> out.writeBytes(b.getBytes()));
        try {
            int from = 0;
            for (var split : splits) {
                rewriter.accept(Buffer.buffer(java.util.Arrays.copyOfRange(body, from, split)));
                from = split;
            }
            rewriter.accept(Buffer.buffer(java.util.Arrays.copyOfRange(body, from, body.length)));
            var result = rewriter.end();
            return new Outcome(result.path(), out.toByteArray(), null);
        } catch (VertexBodyRewriter.InvalidBody | VertexBodyRewriter.RefusedBody e) {
            return Outcome.of(e);
        }
    }

    static Outcome legacy(byte[] body) {
        try {
            var t = LegacyVertexTranslation.translate(PATH, body, PROJECT, REGION);
            return new Outcome(t.path(), t.body(), null);
        } catch (RuntimeException e) {
            return Outcome.of(e);
        }
    }

    /** A JSON text with every object's keys sorted and numbers as jjq writes them, to compare by meaning. */
    static String canonical(byte[] json) {
        var text = new String(json, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) return "";
        return canonical(JqValues.parseStrict(json));
    }

    static String canonical(JqValue value) {
        if (value instanceof JqObject object) {
            var sorted = new TreeMap<String, String>();
            object.forEach((k, v) -> sorted.put(k, canonical(v)));
            var sb = new StringBuilder("{");
            sorted.forEach((k, v) -> {
                if (sb.length() > 1) sb.append(',');
                sb.append(quote(k)).append(':').append(v);
            });
            return sb.append('}').toString();
        }
        if (value instanceof JqArray array) {
            var sb = new StringBuilder("[");
            for (var e : array.asList()) {
                if (sb.length() > 1) sb.append(',');
                sb.append(canonical(e));
            }
            return sb.append(']').toString();
        }
        return value.toJsonString();
    }

    static String quote(String s) {
        var b = JqObject.builder(1);
        b.put("k", s);
        var json = b.build().toJsonString();
        return json.substring(json.indexOf(':') + 1, json.length() - 1);
    }

    /** The rewriter agrees with the tree translation on {@code body}, whole and split anywhere. */
    static void assertParity(String label, byte[] body) {
        var expected = legacy(body);
        var splits = new ArrayList<int[]>();
        splits.add(new int[0]);
        for (int i = 1; i < body.length && body.length <= 400; i++) splits.add(new int[] {i});
        if (body.length > 400) {
            var random = new Random(body.length);
            for (int i = 0; i < 20; i++) splits.add(new int[] {1 + random.nextInt(body.length - 1)});
        }
        if (body.length <= 400) {
            var each = new int[Math.max(0, body.length - 1)];
            for (int i = 0; i < each.length; i++) each[i] = i + 1;
            splits.add(each);
        }
        for (var split : splits) {
            var got = rewrite(body, split);
            var where = label + " split at " + java.util.Arrays.toString(split).substring(0, Math.min(40, java.util.Arrays.toString(split).length()));
            if (expected.refusal() != null) {
                assertInstanceOf(VertexBodyRewriter.InvalidBody.class, got.refusal(),
                        where + ": the tree translation refused it (" + expected.refusal().getMessage() + ")");
                continue;
            }
            assertNull(got.refusal(), () -> where + ": refused " + got.refusal());
            assertEquals(expected.path(), got.path(), where);
            if (expected.path().equals(PATH)) {
                // Not an object: forwarded as it was, but for whitespace before it
                assertEquals(withoutLeadingWhitespace(expected.body()), withoutLeadingWhitespace(got.body()), where);
            } else {
                assertEquals(canonical(expected.body()), canonical(got.body()), where);
            }
        }
    }

    /** The bytes as text, without the whitespace and byte order mark before the value. */
    static String withoutLeadingWhitespace(byte[] body) {
        return new String(body, StandardCharsets.ISO_8859_1).replaceFirst("^([ \t\r\n]|\u00EF\u00BB\u00BF)+", "");
    }

    static List<Object[]> corpus() {
        var cases = new ArrayList<Object[]>();
        add(cases, "claude code", """
                {"model":"claude-sonnet-4-6-20250929","max_tokens":32000,"messages":[{"role":"user","content":[{"type":"text","text":"hi","cache_control":{"type":"ephemeral","scope":"global"}}]}],"system":[{"type":"text","text":"You are","cache_control":{"type":"ephemeral","ttl":"1h","scope":"x"}}],"tools":[{"name":"Bash","input_schema":{"type":"object","properties":{"cache_control":{"scope":1}}}}],"metadata":{"user_id":"u"},"thinking":{"type":"enabled","budget_tokens":1024},"context_management":{"edits":[]},"stream":true}""");
        add(cases, "pi", """
                {"model":"claude-opus-4-1","messages":[{"role":"user","content":"x"}],"max_tokens":5,"temperature":0.7,"top_p":1.0e0,"top_k":5,"stop_sequences":["\\n\\nHuman:"],"tool_choice":{"type":"auto"}}""");
        add(cases, "stream last, model last", """
                {"messages":[],"max_tokens":1,"stream":false,"model":"m-20250101"}""");
        add(cases, "client anthropic_version", """
                {"anthropic_version":"bedrock-2023-05-31","messages":[],"model":"m"}""");
        add(cases, "escaped names", """
                {"\\u006dodel":"m","str\\u0065am":true,"messages":[{"cache_\\u0063ontrol":{"\\u0073cope":"g","type":"e"}}]}""");
        add(cases, "cache_control not an object", """
                {"messages":[{"cache_control":[{"cache_control":{"scope":1,"a":2}}]},{"cache_control":"x"},{"cache_control":null}],"model":"m"}""");
        add(cases, "nested cache_control is copied as it is", """
                {"messages":[{"cache_control":{"inner":{"cache_control":{"scope":1}},"scope":[1,{"scope":2}]}}]}""");
        add(cases, "top-level cache_control is dropped", """
                {"cache_control":{"scope":1},"messages":[]}""");
        add(cases, "model coercions", "{\"model\":42}");
        add(cases, "model true", "{\"model\":true}");
        add(cases, "model null", "{\"model\":null,\"messages\":[]}");
        add(cases, "model array", "{\"model\":[1,{\"a\":2}],\"messages\":[]}");
        add(cases, "model object", "{\"model\":{\"a\":[]}}");
        add(cases, "model empty", "{\"model\":\"\"}");
        add(cases, "model with escapes", "{\"model\":\"cl\\u0061ude\\u00e9\"}");
        add(cases, "stream coercions", "{\"stream\":\"true\",\"messages\":[]}");
        add(cases, "stream 1", "{\"stream\":1}");
        add(cases, "stream array", "{\"stream\":[true]}");
        add(cases, "stream null", "{\"stream\":null}");
        add(cases, "empty object", "{}");
        add(cases, "only dropped", "{\"x\":1,\"y\":{\"z\":[1,2]}}");
        add(cases, "numbers", "{\"messages\":[0,-0,1.5,-1.5e10,1E+2,1e-2,12345678901234567890123,0.0]}");
        add(cases, "strings", "{\"messages\":[\"\",\"\\\"\\\\\\/\\b\\f\\n\\r\\t\",\"\\u0000\\u001f\",\"caf\u00e9 \u2603 \uD83D\uDE00\",\"a\tb\"]}");
        add(cases, "literals", "{\"messages\":[true,false,null,[],{}]}");
        add(cases, "whitespace", " \r\n\t{ \"messages\" : [ 1 , { \"a\" : \"b\" } ] , \"model\" : \"m\" } \n");
        add(cases, "byte order mark", "\uFEFF{\"messages\":[]}");
        add(cases, "array body", "[1,2,{\"model\":\"m\"}]");
        add(cases, "string body", "\"just a string\"");
        add(cases, "number body", "42");
        add(cases, "number body with space", " -1.5e3 ");
        add(cases, "literal body", "null");
        add(cases, "empty body", "");
        add(cases, "whitespace body", "   \n");
        // Invalid, as the tree translation refused them
        for (var bad : new String[] {
                "{", "}", "{\"a\":1", "{\"a\" 1}", "{\"a\":}", "{,}", "{\"a\":1,}", "{\"messages\":[1,]}",
                "{\"messages\":[,1]}", "{\"a\":01}", "{\"a\":+1}", "{\"a\":1.}", "{\"a\":.5}", "{\"a\":1e}",
                "{\"a\":-}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12g4\"}", "{\"a\":tru}",
                "{\"a\":true1}", "{\"a\":1}{}", "{\"a\":1}x", "{\"a\":1}\u0000", "{\"a\":NaN}", "{\"a\":\"x}",
                "{'a':1}", "{\"a\":1}]", "[", "[1", "\"x", "tru", "-", "01", "{\"a\":[}", "{\"a\":{]}",
                "\uFEFF\uFEFF{}", "{\"messages\":[1 2]}", "{\"a\"::1}", "{\uFEFF}", "{}\uFEFF"}) {
            add(cases, "invalid " + bad.replace("\u0000", "\\0"), bad);
        }
        add(cases, "byte order mark after whitespace", " \n\uFEFF {\"messages\":[]}");
        add(cases, "byte order mark before an array", "\uFEFF [1]");
        cases.add(new Object[] {"depth 500", deep(500)});
        cases.add(new Object[] {"object depth 500", deepObject(500)});
        cases.add(new Object[] {"broken byte order mark", new byte[] {(byte) 0xEF, (byte) 0xBB, '{', '}'}});
        return cases;
    }

    static void add(List<Object[]> cases, String label, String body) {
        cases.add(new Object[] {label, body.getBytes(StandardCharsets.UTF_8)});
    }

    static byte[] deep(int levels) {
        var s = "{\"messages\":" + "[".repeat(levels - 1) + "]".repeat(levels - 1) + "}";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] deepObject(int levels) {
        var sb = new StringBuilder();
        for (int i = 1; i < levels; i++) sb.append("{\"messages\":");
        sb.append("{}");
        sb.append("}".repeat(levels - 1));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Nesting is bounded as {@code parseStrict} bounded it, at 10,000 levels. The tree translation
     * could not reach that far: its recursion overflowed the stack a few thousand levels down, an
     * Error nothing caught, which left the client without an answer.
     */
    @Test
    void depthIsBoundedWhereParseStrictBoundedIt() {
        assertNull(rewrite(deep(VertexBodyRewriter.MAX_DEPTH)).refusal());
        assertNull(rewrite(deepObject(VertexBodyRewriter.MAX_DEPTH)).refusal());
        assertInstanceOf(VertexBodyRewriter.InvalidBody.class, rewrite(deep(VertexBodyRewriter.MAX_DEPTH + 1)).refusal());
        assertInstanceOf(VertexBodyRewriter.InvalidBody.class, rewrite(deepObject(VertexBodyRewriter.MAX_DEPTH + 1)).refusal());
        assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict(deep(VertexBodyRewriter.MAX_DEPTH + 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void matchesTheTreeTranslation(String label, byte[] body) {
        assertParity(label, body);
    }

    /** Request-shaped bodies, generated: every one translated as the tree translation did. */
    @Test
    void matchesTheTreeTranslationOnGeneratedBodies() {
        var random = new Random(1315);
        for (int i = 0; i < 400; i++) {
            var body = new Generator(random).body().getBytes(StandardCharsets.UTF_8);
            assertParity("generated #" + i + " " + new String(body, StandardCharsets.UTF_8), body);
        }
    }

    /** Bodies broken a byte at a time: refused exactly when the tree translation refused them. */
    @Test
    void refusesWhatTheTreeTranslationRefused() {
        var random = new Random(4242);
        var significant = "{}[],:\"\\0-e.tfn x1".toCharArray();
        int compared = 0;
        for (int i = 0; i < 3000; i++) {
            var body = new StringBuilder(new Generator(random).body());
            var at = random.nextInt(body.length());
            switch (random.nextInt(3)) {
                case 0 -> body.deleteCharAt(at);
                case 1 -> body.insert(at, significant[random.nextInt(significant.length)]);
                default -> body.setCharAt(at, significant[random.nextInt(significant.length)]);
            }
            var bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            var expected = legacy(bytes);
            // Duplicate keys are the one deliberate departure: tested on their own below
            if (expected.refusal() != null && String.valueOf(expected.refusal().getMessage()).contains("Duplicate key")) continue;
            var got = rewrite(bytes);
            if (got.refusal() instanceof VertexBodyRewriter.RefusedBody) {
                // A mutation that made the body name its model or stream twice, or a model with a slash
                continue;
            }
            assertEquals(expected.refusal() != null, got.refusal() != null,
                    () -> "for " + body + ": tree " + expected.refusal() + ", rewriter " + got.refusal());
            compared++;
        }
        assertTrue(compared > 2500, "compared only " + compared);
    }

    // --- Where it parts from the tree translation, as #1315 decided ---

    static String body(Outcome outcome) {
        assertNull(outcome.refusal(), () -> "refused: " + outcome.refusal());
        return new String(outcome.body(), StandardCharsets.UTF_8);
    }

    static Outcome rewrite(String body) {
        return rewrite(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void duplicateModelOrStreamIsRefused() {
        for (var body : new String[] {
                "{\"model\":\"a\",\"model\":\"b\"}",
                "{\"model\":\"a\",\"\\u006dodel\":\"b\"}",
                "{\"model\":1,\"model\":\"b\"}",
                "{\"stream\":true,\"stream\":false}",
                "{\"stream\":false,\"stre\\u0061m\":true}"}) {
            assertInstanceOf(VertexBodyRewriter.RefusedBody.class, rewrite(body).refusal(), body);
        }
    }

    @Test
    void duplicatesInUntouchedFieldsAreForwarded() {
        var out = body(rewrite("{\"messages\":[{\"role\":\"user\",\"role\":\"x\"}],\"messages\":[2],\"model\":\"m\"}"));
        assertEquals("{\"messages\":[{\"role\":\"user\",\"role\":\"x\"}],\"messages\":[2],\"anthropic_version\":\"vertex-2023-10-16\"}", out);
    }

    /** A duplicate never carries a field, or a scope, past the rewrite. */
    @Test
    void duplicatesCannotBypassARewrite() {
        var out = body(rewrite("""
                {"context_management":1,"context_management":2,"anthropic_version":"a","anthr\\u006fpic_version":"b",
                 "messages":[{"cache_control":{"scope":"a","type":"e","scope":"b","\\u0073cope":"c"},
                              "cache_control":{"scope":"d"}}]}"""));
        assertEquals("{\"messages\":[{\"cache_control\":{\"type\":\"e\"},\"cache_control\":{}}],"
                + "\"anthropic_version\":\"vertex-2023-10-16\"}", out);
    }

    @Test
    void malformedInterpretedNamesAreRefused() {
        var cases = new ArrayList<byte[]>();
        cases.add("{\"\\ud800\":1}".getBytes(StandardCharsets.UTF_8));
        cases.add("{\"messages\":[{\"a\\udc00\":1}]}".getBytes(StandardCharsets.UTF_8));
        cases.add("{\"messages\":[{\"cache_control\":{\"s\\ud800\\u0063ope\":1}}]}".getBytes(StandardCharsets.UTF_8));
        // "scope" with its 'o' spelled as an overlong two-byte sequence
        cases.add(bytes("{\"messages\":[{\"cache_control\":{\"sc", 0xC1, 0xAF, "pe\":1}}]}"));
        // An encoded surrogate, and a truncated sequence
        cases.add(bytes("{\"", 0xED, 0xA0, 0x80, "\":1}"));
        cases.add(bytes("{\"mod", 0xC3, "el\":\"m\"}"));
        for (var body : cases) {
            assertInstanceOf(VertexBodyRewriter.RefusedBody.class, rewrite(body).refusal(),
                    new String(body, StandardCharsets.ISO_8859_1));
        }
    }

    /** Names nobody compares (below a skipped field, inside a copied cache_control value) are left alone. */
    @Test
    void malformedNamesNobodyReadsAreLeftAlone() {
        var skipped = bytes("{\"context_management\":{\"", 0xC1, 0xAF, "\":1},\"messages\":[]}");
        assertNull(rewrite(skipped).refusal());
        var copied = bytes("{\"messages\":[{\"cache_control\":{\"type\":{\"", 0xC1, 0xAF, "\":1}}}]}");
        var out = rewrite(copied);
        assertNull(out.refusal());
        assertTrue(contains(out.body(), new byte[] {(byte) 0xC1, (byte) 0xAF}), "copied as it was sent");
    }

    @Test
    void routingModelMustBeUnambiguous() {
        for (var model : new String[] {"a/b", "a?b", "a#b", "a%2Fb", "a b", "a\\tb", "a\\u0000b", "a\\u007fb",
                "a\\u00a0b", "a\\ud800b", "a\\udc00", "a\\u0085b", "a\\\\b", "a\\u200bb", "a\\ufeffb", "a\\u202eb"}) {
            var body = "{\"model\":\"" + model + "\"}";
            assertInstanceOf(VertexBodyRewriter.RefusedBody.class, rewrite(body).refusal(), body);
        }
        assertInstanceOf(VertexBodyRewriter.RefusedBody.class, rewrite(bytes("{\"model\":\"a", 0xFF, "\"}")).refusal());
        assertEquals("/v1/projects/proj/locations/us-east5/publishers/anthropic/models/caf\u00e9-\uD83D\uDE00:rawPredict",
                rewrite("{\"model\":\"caf\\u00e9-\uD83D\uDE00\"}").path());
    }

    /** {@code model} is held up to the Vertex front end's URL ceiling, and no further. */
    @Test
    void modelIsBoundedByTheUrlCeiling() {
        var prefix = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/";
        var room = VertexBodyRewriter.MAX_URL_PATH - prefix.length() - ":rawPredict".length();
        var fits = rewrite("{\"model\":\"" + "a".repeat(room) + "\"}");
        assertEquals(VertexBodyRewriter.MAX_URL_PATH, fits.path().length());
        assertInstanceOf(VertexBodyRewriter.RefusedBody.class, rewrite("{\"model\":\"" + "a".repeat(room + 1) + "\"}").refusal());
        assertInstanceOf(VertexBodyRewriter.RefusedBody.class,
                rewrite("{\"model\":\"" + "a".repeat(room) + "\",\"stream\":true}").refusal(),
                "the longer endpoint does not fit");
        assertInstanceOf(VertexBodyRewriter.RefusedBody.class,
                rewrite("{\"model\":\"" + "a".repeat(10 * VertexBodyRewriter.MAX_URL_PATH) + "\"}").refusal());
    }

    /** Values the rewriter keeps are forwarded byte for byte, malformed Unicode and number spelling included. */
    @Test
    void untouchedValuesAreForwardedAsSent() {
        var body = bytes("{\"messages\":[\"\\ud800 lone\",\"", 0xFF, 0xC1, 0xAF, "\",1.0e2,-0,0.10],\"model\":\"m\"}");
        var out = rewrite(body);
        assertNull(out.refusal());
        var expected = bytes("{\"messages\":[\"\\ud800 lone\",\"", 0xFF, 0xC1, 0xAF, "\",1.0e2,-0,0.10],"
                + "\"anthropic_version\":\"vertex-2023-10-16\"}");
        assertArrayEquals(expected, out.body());
    }

    @Test
    void droppedFieldsAreNamedBoundedly() throws Exception {
        var longName = "x".repeat(10_000);
        var out = new ByteArrayOutputStream();
        var rewriter = new VertexBodyRewriter(PATH, PROJECT, REGION, b -> out.writeBytes(b.getBytes()));
        rewriter.accept(Buffer.buffer("{\"context_management\":1,\"" + longName + "\":2,\"messages\":[]}"));
        var dropped = rewriter.end().droppedFields();
        assertEquals("context_management", dropped.get(0));
        assertTrue(dropped.get(1).length() <= VertexBodyRewriter.LOGGED_NAME_BYTES + 1, dropped.get(1));
        assertTrue(dropped.get(1).endsWith("…"));
    }

    /** A body may drop any number of fields; it reports a few distinct names, and holds no more. */
    @Test
    void droppedNamesAreBoundedWhateverTheBody() throws Exception {
        var out = new ByteArrayOutputStream();
        var rewriter = new VertexBodyRewriter(PATH, PROJECT, REGION, b -> out.writeBytes(b.getBytes()));
        rewriter.accept(Buffer.buffer("{"));
        for (int i = 0; i < 100_000; i++) rewriter.accept(Buffer.buffer("\"x" + (i % 1000) + "\":0,\"x\":0,"));
        rewriter.accept(Buffer.buffer("\"messages\":[]}"));
        var dropped = rewriter.end().droppedFields();
        assertEquals(VertexBodyRewriter.MAX_DROPPED_NAMES, dropped.size());
        assertEquals("{\"messages\":[],\"anthropic_version\":\"vertex-2023-10-16\"}", out.toString(StandardCharsets.UTF_8));
    }

    static byte[] bytes(Object... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            if (part instanceof String s) out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
            else out.write((Integer) part);
        }
        return out.toByteArray();
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Messages-shaped JSON with the shapes the rewriter acts on, and no duplicate keys. */
    static final class Generator {
        private final Random random;
        private static final String[] TOP = {"model", "messages", "system", "max_tokens", "temperature", "stream",
                "metadata", "tools", "tool_choice", "thinking", "output_config", "anthropic_version",
                "context_management", "betas", "container", "mcp_servers", "\\u006dodel_x", "top_k"};
        private static final String[] NAMES = {"type", "text", "role", "content", "cache_control", "scope", "ttl",
                "name", "input_schema", "c\\u0061che_control", "sc\\u006fpe", "id", "data"};

        Generator(Random random) {
            this.random = random;
        }

        String body() {
            var sb = new StringBuilder("{");
            var keys = new ArrayList<>(List.of(TOP));
            java.util.Collections.shuffle(keys, random);
            var count = 1 + random.nextInt(keys.size());
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(',');
                sb.append(ws()).append('"').append(keys.get(i)).append('"').append(ws()).append(':').append(ws());
                sb.append(switch (keys.get(i)) {
                    // Not a string sometimes, to exercise the coercions; never a string with a space or a
                    // slash, which the rewriter refuses where the tree translation did not
                    case "model" -> random.nextInt(5) == 0 ? number() : random.nextInt(5) == 0 ? object(1)
                            : "\"claude-" + random.nextInt(9) + (random.nextBoolean() ? "-20250929" : "") + "\"";
                    case "stream" -> random.nextInt(4) == 0 ? value(1) : String.valueOf(random.nextBoolean());
                    default -> value(0);
                });
            }
            return sb.append(ws()).append('}').toString();
        }

        String ws() {
            return switch (random.nextInt(8)) {
                case 0 -> " ";
                case 1 -> "\n  ";
                default -> "";
            };
        }

        String value(int depth) {
            var kind = depth > 5 ? random.nextInt(4) : random.nextInt(7);
            return switch (kind) {
                case 0 -> string();
                case 1 -> number();
                case 2 -> new String[] {"true", "false", "null"}[random.nextInt(3)];
                case 3 -> string();
                case 4, 5 -> object(depth + 1);
                default -> array(depth + 1);
            };
        }

        String object(int depth) {
            var names = new ArrayList<>(List.of(NAMES));
            java.util.Collections.shuffle(names, random);
            // One spelling per name, so no object holds a duplicate once escapes are read
            names.removeIf(n -> n.startsWith("c\\u0061") && names.contains("cache_control"));
            names.removeIf(n -> n.startsWith("sc\\u006f") && names.contains("scope"));
            var sb = new StringBuilder("{");
            var count = random.nextInt(4);
            for (int i = 0; i < count && i < names.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(ws()).append('"').append(names.get(i)).append("\":").append(ws()).append(value(depth));
            }
            return sb.append('}').toString();
        }

        String array(int depth) {
            var sb = new StringBuilder("[");
            var count = random.nextInt(4);
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(',').append(ws());
                sb.append(value(depth));
            }
            return sb.append(']').toString();
        }

        String string() {
            var parts = new String[] {"hello", "\\n", "\\\"", "\\u00e9", "\u00e9", "\u2603", "\\uD83D\\uDE00",
                    "\uD83D\uDE00", "\\\\", "\\/", " ", "\\t", "x"};
            var sb = new StringBuilder("\"");
            var count = random.nextInt(5);
            for (int i = 0; i < count; i++) sb.append(parts[random.nextInt(parts.length)]);
            return sb.append('"').toString();
        }

        String number() {
            return new String[] {"0", "-0", "1", "-12", "3.25", "1e5", "1E-2", "-0.5e+3", "123456789012345678901"}
                    [random.nextInt(9)];
        }
    }
}
