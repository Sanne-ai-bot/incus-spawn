package dev.incusspawn.mcp;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import java.util.ArrayList;

/** Bounded JSONL tails, plus the durable terminal record appended by TaskScripts.status. */
final class CodexEvents {
    static StreamJsonEvents.Summary summarize(String tail, int keep) {
        var recent = new ArrayList<String>();
        int messages = 0;
        JqValue result = null;
        for (var line : tail.split("\n")) {
            try {
                // A partial line is skipped, as Jackson's readTree threw on it
                var e = Jjq.parseForeign(line, "Codex's event");
                if (!(e instanceof JqObject)) continue;
                var type = JsonRpc.text(e, "type", "");
                if (type.equals("isx.codex.result")) {
                    var success = JsonRpc.bool(e, "success", false);
                    result = JqObject.builder(6)
                            .put("subtype", success ? "success" : "error")
                            .put("is_error", !success)
                            .put("result", JsonRpc.text(e, "error", ""))
                            .put("usage", e.getField("usage"))
                            .put("report_bytes", e.getField("report_bytes"))
                            .put("report_truncated", e.getField("report_truncated"))
                            .build();
                } else if (java.util.List.of("item.started", "item.updated", "item.completed").contains(type)) {
                    var item = e.getField("item");
                    var itemType = JsonRpc.text(item, "type", "");
                    if (itemType.equals("agent_message") && type.equals("item.completed")) messages++;
                    var text = itemType + ": " + JsonRpc.orDefault(item.getField("text"),
                            JsonRpc.orDefault(item.getField("command"), JsonRpc.text(item, "status", "")));
                    recent.add(text.substring(0, Math.min(300, text.length())));
                    while (recent.size() > keep) recent.removeFirst();
                }
            } catch (Exception ignored) { /* partial lines and future events are harmless */ }
        }
        return new StreamJsonEvents.Summary(recent, messages, result);
    }

    static String runner() {
        try (var in = CodexEvents.class.getResourceAsStream("/mcp/codex-runner.cjs")) {
            if (in == null) throw new IllegalStateException("missing Codex runner resource");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read Codex runner", e);
        }
    }
}
